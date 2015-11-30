package net.danielkza.http2.stream

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try
import scalaz.\/
import scalaz.syntax.either._
import akka.util.Timeout
import akka.actor._
import akka.pattern.ask
import akka.stream._
import akka.stream.stage._
import net.danielkza.http2.util.ArrayQueue
import net.danielkza.http2.protocol.{HTTP2Error, Http2Stream, Frame}

class InboundStreamDispatcher(val outputPorts: Int, val bufferSize: Int, manager: => ActorRef)
                             (implicit ec: ExecutionContext, timeout: Timeout = 5.seconds)
  extends GraphStage[UniformFanOutShape[Frame, Frame]]
{
  import HTTP2Error._
  import StreamManagerActor._
  import Http2Stream._

  val in: Inlet[Frame] = Inlet[Frame]("InboundStreamDispatcher.in")
  val outs: Array[Outlet[Frame]] = Array.tabulate(outputPorts) { i  =>
    Outlet[Frame]("InboundFrameDispatcher.out" + i)
  }

  override val shape = UniformFanOutShape[Frame, Frame](in, outs: _*)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) { logic =>
    private val queues = Array.fill(outputPorts)(new ArrayQueue[Frame](bufferSize))
    private val demands = Array.fill(outputPorts)(false)
    private val assignedStreams = Array.fill(outputPorts)(0)
    private val streamManager = manager
    private var finishing = -1

    private val resultCallback = getAsyncCallback[Try[OutMessage]] {
      case scala.util.Success(reply) => reply match {
        case Failure(error) =>
          failStage(error)
        case IncomingAction(stream, Skip) =>
          // skip
        case IncomingAction(stream, Continue(frame)) =>
          getOrAssignPort(stream).map { port =>
            queues(port).add(frame)
            dispatchAll()
          }.valueOr { error => failStage(error) }
        case IncomingAction(stream, Finish(frame)) =>
          getOrAssignPort(stream).map { port =>
            queues(port).add(frame)
            dispatchAll()
            assignedStreams(port) = 0
          }.valueOr { error => failStage(error) }
        case IncomingAction(stream, Stop) =>
          // pass
        case _ =>
          failStage(new RuntimeException("Unexpected response from StreamManagerActor"))
      }
      case scala.util.Failure(error) =>
        failStage(error)
    }

    private val errorCallback = getAsyncCallback[Throwable](failStage)

    private def getOrAssignPort(stream: Int): \/[HTTP2Error, Int] = {
      var i: Int = 0
      var free: Int = -1
      while(i < outputPorts) {
        if(assignedStreams(i) == stream) return i.right
        if(free == -1 && assignedStreams(i) == 0) free = i

        i += 1
      }

      if(free != -1) {
        assignedStreams(free) = stream
        free.right
      } else {
        RefusedStream().left
      }
    }

    private def getAssignedPort(stream: Int): \/[HTTP2Error, Int] = {
      assignedStreams.indexOf(stream) match {
        case -1 => InvalidStream().left
        case i  => i.right
      }
    }

    private def backed: Boolean =
      queues.exists(_.isFull)

    private def enqueueIn(): Unit = {
      val frame = grab(in)
      println(s"InboundStreamDispatcher: enqueueIn $frame"); System.out.flush()

      (streamManager ? IncomingFrame(frame)).mapTo[OutMessage].onComplete(resultCallback.invoke)
    }

    private def dispatchAll(): Unit = {
      var port: Int = 0
      while(port < outputPorts) {
        val out = outs(port)

        val queue = queues(port)
        if(demands(port) && !queue.isEmpty) {
          val frame = queue.remove()
          println(s"InboundStreamDispatcher: pushing $frame to $port"); System.out.flush()
          push(out, frame)
          demands(port) = false
        }

        port += 1
      }
    }

    def finishIn(): Unit = {
      println(s"InboundStreamDispatcher: finishing"); System.out.flush()
      finishing = outputPorts

      var port: Int = 0
      while(port < outputPorts) {
        val out = outs(port)
        val queue = queues(port)

        if(!queue.isEmpty)
          emitMultiple(out, queue.iterator().asScala, () => completeOut(out))
        else
          completeOut(out)

        port += 1
      }
    }

    private def completeOut(out: Outlet[Frame]): Unit = {
      complete(out)

      finishing -= 1
      if(finishing == 0) {
        completeStage()
        finishing = -1
      }
    }

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        enqueueIn()
        if(!isClosed(in) && !hasBeenPulled(in) && demands.exists(b => b) && !backed) {
          println(s"InboundStreamDispatcher: pulling inlet after push"); System.out.flush()
          pull(in)
        }
      }

      override def onUpstreamFinish(): Unit =
        finishIn()
    })

    for(port <- 0 until outputPorts) {
      val out = outs(port)
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          println(s"InboundStreamDispatcher: $port pulled"); System.out.flush()
          queues(port).poll() match {
            case null =>
              demands(port) = true
              if(!hasBeenPulled(in) && !backed) {
                println(s"InboundStreamDispatcher: pulling inlet"); System.out.flush()
                pull(in)
              }
            case elm =>
              push(out, elm)
          }
        }

        override def onDownstreamFinish(): Unit = {
          if(finishing < 0) {
            failStage(new RuntimeException(s"Outlet finished too early: $out"))
          } else {
            super.onDownstreamFinish()
          }
        }
      })
    }

    override def preStart(): Unit = () //pull(in)
  }

  override def toString = "InboundStreamDispatcher"
}

package net.danielkza.http2.protocol

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.{Future, Promise}
import scala.util.Success
import scalaz.\/
import scalaz.syntax.either._
import net.danielkza.http2.util.ArrayQueue
import net.danielkza.http2.protocol.HTTP2Error._

abstract class Http2Stream(initialState: Http2Stream.State) {
  import Http2Stream._
  import Frame._

  protected var _state = initialState
  protected var _inFlowWindow = 65535
  protected var _outFlowWindow = 65535
  protected var _inWeight = 16
  protected var _outWeight = 16
  protected var _closeError: Option[HTTP2Error] = None

  def id: Int

  final def state: State = _state
  protected[http2] def state_=(s: State): Unit = _state = s

  final def inWeight: Int = _inWeight
  protected[http2] def inWeight_=(w: Int): Unit = _inWeight = w
  final def outWeight: Int = _outWeight
  protected[http2] def outWeight_=(w: Int): Unit = _outWeight = w

  final def inFlowWindow: Int = _inFlowWindow
  protected[http2] final def inFlowWindow_=(w: Int): Unit= _inFlowWindow = w
  protected[http2] def incrementInFlowWindow(inc: Int): Unit = _inFlowWindow += inc
  protected[http2] def decrementInFlowWindow(dec: Int): Unit = _inFlowWindow -= dec

  final def outFlowWindow: Int = _outFlowWindow
  protected[http2] final def outFlowWindow_=(w: Int) = _outFlowWindow = w
  protected[http2] def incrementOutFlowWindow(inc: Int): Unit = _outFlowWindow += inc
  protected[http2] def decrementOutFlowWindow(dec: Int): Unit = _outFlowWindow -= dec

  protected[http2] def inWindowAvailable(size: Int): Boolean
  protected[http2] def outWindowAvailable(size: Int): Boolean

  final def isClosed: Boolean = state.isClosed
  def closeError: Option[HTTP2Error] = closeError.filter(_ => this.isClosed)

  private def updateState(newState: Option[State]) = {
    (state, newState) match {
      case (_, Some(s)) =>
        s.right
      case (Closed | HalfClosedByLocal | HalfClosedByRemote, _) =>
        StreamClosedError("Frame received on closed stream").left
      case _ =>
        UnacceptableFrameError().left
    }
  }

  def receive(frame: Frame): \/[HTTP2Error, State] = {
    import HTTP2Error.Codes._

    state.onReceive.lift(frame).map { newState =>
      (newState, frame) match {
        case (Closed, ResetStream(_, errorCode)) =>
          close(HTTP2Error.Standard.fromCode(errorCode))
          if(errorCode == CANCEL || errorCode == REFUSED_STREAM)
            Closed.right // Don't fail the current call for a simple stream reset
          else
            closeError.get.left
        case (_, d: Data) if !inWindowAvailable(d.data.length) =>
          FlowControlError().left
        case (_, _) =>
          updateState(Some(newState))
      }
    }.getOrElse(updateState(None))
  }

  def send(frame: Frame): \/[HTTP2Error, State] = {
    val newState = state.onSend.lift(frame)
    updateState(newState)
  }

  def close(error: HTTP2Error): Closed.type = {
    if(isClosed) throw new IllegalStateException("Stream already closed")
    _closeError = Some(error)
    updateState(Some(Closed))
    Closed
  }
}

class ControlStream private[http2] extends Http2Stream(Http2Stream.Control) {
  override val id = 0

  val streams = new mutable.LongMap[DataStream]
  val delayedStreamsQueue = mutable.Queue.empty[DataStream]
  protected var _initialInFlowWindow = 65535
  protected var _initialOutFlowWindow = 65535

  protected[http2] def inWindowAvailable(size: Int): Boolean =
    inFlowWindow >= size

  protected[http2] def outWindowAvailable(size: Int): Boolean =
    outFlowWindow >= size

  protected[http2] override def incrementOutFlowWindow(inc: Int): Unit = {
    super.incrementOutFlowWindow(inc)
    runOutQueue()
  }

  protected[http2] def runOutQueue(): Unit = {
    while(outFlowWindow > 0) {
      val nextStream = delayedStreamsQueue.dequeueFirst(_.possibleProgressForWindow(outFlowWindow) > 0)
      nextStream match {
        case Some(s) => s.runOutQueue()
        case None => return
      }
    }
  }

  final def initialInFlowWindow: Int = _initialInFlowWindow
  protected[http2] def initialInFlowWindow_=(w: Int): Unit = _initialInFlowWindow = w
  final def initialOutFlowWindow: Int = _initialOutFlowWindow
  protected[http2] def initialOutFlowWindow_=(w: Int): Unit = _initialOutFlowWindow = w

  protected[http2] def addStream(dataStream: DataStream): Unit = {
    streams(dataStream.id) = dataStream
    dataStream.inFlowWindow = initialInFlowWindow
    dataStream.outFlowWindow = initialOutFlowWindow
  }
}

class DataStream private[http2] (
  val id: Int,
  initialState: Http2Stream.State,
  controlStream: ControlStream,
  flowControlQueueSize: Int = 8)
extends Http2Stream(initialState) {
  import Http2Stream._
  import Frame._
  import HTTP2Error._

  protected var _parentStream = 0

  protected var delayedDataQueue = new ArrayQueue[(Promise[Data], Data)](flowControlQueueSize)

  def parentStream: Int = _parentStream
  protected[http2] def parentStream_=(p: Int) = _parentStream = p

  protected[http2] override def inWindowAvailable(size: Int): Boolean =
    controlStream.inWindowAvailable(size) && ownInWindowAvailable(size)

  protected[http2] def ownInWindowAvailable(size: Int): Boolean =
    inFlowWindow >= size

  protected[http2] override def outWindowAvailable(size: Int): Boolean =
    controlStream.outWindowAvailable(size) && ownOutWindowAvailable(size)

  protected[http2] def ownOutWindowAvailable(size: Int): Boolean =
    outFlowWindow >= size


  override protected[http2] def incrementOutFlowWindow(inc: Int): Unit = {
    super.incrementOutFlowWindow(inc)
    runOutQueue()
  }

  protected[http2] def possibleProgressForWindow(window: Int): Int = {
    var size = 0
    delayedDataQueue.iterator().asScala.foreach { case (_, data) =>
      if(size + data.data.length > window)
        return size

      size += data.data.length
    }

    size
  }

  protected[http2] def runOutQueue(): Unit = {
    while(!delayedDataQueue.isEmpty) {
      val (promise, data) = delayedDataQueue.peek()
      if(outWindowAvailable(data.data.length)) {
        decrementOutFlowWindow(data.data.length)
        controlStream.decrementOutFlowWindow(data.data.length)
        delayedDataQueue.remove()

        promise.complete(Success(data))
      } else {
        return
      }
    }
  }

  protected[http2] def acceptOutData(data: Data, closing: Boolean = false): Future[Data] = {
    state match {
      case Open | ReservedForRemote | HalfClosedByRemote =>
        if(delayedDataQueue.isFull) {
          Future.failed(FlowControlError())
        } else if(delayedDataQueue.isEmpty && outWindowAvailable(data.data.length)) {
          decrementOutFlowWindow(data.data.length)
          controlStream.decrementOutFlowWindow(data.data.length)
          Future.successful(data)
        } else {
          val promise = Promise[Data]
          delayedDataQueue.add((promise, data))
          runOutQueue()
          promise.future
        }
      case _ =>
        Future.failed(UnacceptableFrameError())
    }
  }

  override def close(error: HTTP2Error = NoError()) = {
    val r = super.close(error)
    while(!delayedDataQueue.isEmpty) {
      val (promise, _) = delayedDataQueue.remove()
      promise.failure(error)
    }
    r
  }
}

object Http2Stream {
  import Frame._

  sealed trait ReceiveAction
  sealed trait SendAction
  case class Continue(frame: Frame) extends ReceiveAction with SendAction
  case class Finish(frame: Frame) extends ReceiveAction with SendAction
  case class Delay(f: Future[Data]) extends SendAction
  case object Stop extends ReceiveAction with SendAction
  case object Skip extends ReceiveAction

  sealed trait State {
    def onReceive: PartialFunction[Frame, State] = defaults
    def onSend: PartialFunction[Frame, State] = defaults
    def isClosed: Boolean = false

    def defaults: PartialFunction[Frame, State] = {
      case rst: ResetStream => Closed
      case p: Priority      => this
      case w: WindowUpdate  => this
    }

    def withDefaults(f: PartialFunction[Frame, State]): PartialFunction[Frame, State] =
      f orElse defaults
  }

  case object Control extends State {
    override val onReceive: PartialFunction[Frame, State] = {
      case _: Settings     => this
      case _: Ping         => this
      case _: GoAway       => this
      case _: WindowUpdate => this
    }

    override val onSend: PartialFunction[Frame, State] = {
      case _: Settings     => this
      case _: Ping         => this
      case _: GoAway       => this
      case _: WindowUpdate => this
    }
  }

  case object Idle extends State {
    override val onReceive: PartialFunction[Frame, State] = {
      case h: Headers if h.endStream => HalfClosedByRemote
      case _: Headers                => Open
      case _: Priority               => this
    }

    override val onSend: PartialFunction[Frame, State] = {
      case h: Headers if h.endStream => HalfClosedByLocal
      case _: Headers                => Open
      case _: Priority               => this
    }
  }

  case object ReservedForRemote extends State {
    override val onReceive: PartialFunction[Frame, State] = withDefaults {
      case h: Headers                => HalfClosedByRemote
    }
  }

  case object ReservedForLocal extends State {
    override val onSend: PartialFunction[Frame, State] = withDefaults {
      case h: Headers                => HalfClosedByLocal
    }
  }

  case object Open extends State {
    override val onReceive: PartialFunction[Frame, State] = {
      case d: Data if d.endStream    => HalfClosedByRemote
      case h: Headers if h.endStream => HalfClosedByRemote
      case _: ResetStream            => Closed
      case _                         => this
    }

    override val onSend: PartialFunction[Frame, State] = {
      case d: Data if d.endStream    => HalfClosedByLocal
      case h: Headers if h.endStream => HalfClosedByLocal
      case _: ResetStream            => Closed
      case _                         => this
    }
  }

  case object HalfClosedByLocal extends State {
    override val onReceive: PartialFunction[Frame, State] = {
      case d: Data if d.endStream    => Closed
      case h: Headers if h.endStream => Closed
      case _: ResetStream            => Closed
      case _                         => this
    }
  }

  case object HalfClosedByRemote extends State {
    override val onSend: PartialFunction[Frame, State] = {
      case d: Data if d.endStream    => Closed
      case h: Headers if h.endStream => Closed
      case _: ResetStream            => Closed
      case _                         => this
    }
  }

  case object Closed extends State {
    override def isClosed = true
  }

  case object Dead extends State {
    override def isClosed = true

    override val onReceive = PartialFunction.empty[Frame, State]
    override val onSend    = PartialFunction.empty[Frame, State]
  }

//  sealed trait PriorityTree {
//    def weight: Int
//  }
//
//  object PriorityTree {
//    case class Leaf(stream: Int, weight: Int) extends PriorityTree
//    case class Node(children: PriorityTree, weight: Int) extends PriorityTree
//  }
}

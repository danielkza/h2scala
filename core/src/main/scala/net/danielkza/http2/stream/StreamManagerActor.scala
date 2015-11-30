package net.danielkza.http2.stream

import scalaz.{\/-, -\/}
import akka.actor._
import akka.pattern.pipe
import net.danielkza.http2.protocol._
import net.danielkza.http2.protocol.HTTP2Error._

class StreamManagerActor(managerF: => StreamManager) extends Actor {
  import StreamManagerActor._
  import StreamManager._
  import Http2Stream._

  private var manager: StreamManager = null

  override def preStart(): Unit = {
    super.preStart()
    manager = managerF
  }

  override def postStop(): Unit = {
    manager.closeAll()
    manager = null
    super.postStop()
  }

  def receive: Receive = { case i: InMessage => i match {
    case IncomingFrame(frame) =>
      manager.receive(frame) match {
        case -\/(error) =>
          sender ! Failure(error)
        case \/-(ReceiveReply(stream, action)) =>
          sender ! IncomingAction(stream.id, action)
      }
    case OutgoingFrame(frame) =>
      manager.send(frame) match {
        case -\/(error) =>
          sender ! Failure(error)
        case \/-(SendReply(stream, Delay(dataFuture))) =>
          implicit val ec = context.dispatcher
          val s = sender
          dataFuture.map {
            case d if d.endStream  =>
              OutgoingAction(stream.id, Finish(d))
            case d                 =>
              OutgoingAction(stream.id, Continue(d))
          }.recover {
            case e: HTTP2Exception =>
              Failure(e.error)
            case e                 =>
              Failure(InternalError(s"Unexpected exception: $e"))
          } to s
        case \/-(SendReply(stream, action))=>
          sender ! OutgoingAction(stream.id, action)
      }
    case ReserveStream =>
      manager.reserveOut() match {
        case Some(stream) => sender ! Reserved(stream.id)
        case None         => sender ! Failure(RefusedStream())
      }
    case StreamProcessingStarted(streamId) =>
      manager.markProcessed(streamId)
    case GetLastProcessedStream =>
      sender ! LastProcessedStream(manager.lastProcessedId)
  }}
}

object StreamManagerActor {
  import Http2Stream._

  sealed trait InMessage
  case class IncomingFrame(frame: Frame) extends InMessage
  case class OutgoingFrame(frame: Frame) extends InMessage
  case object ReserveStream extends InMessage
  case class StreamProcessingStarted(stream: Int) extends InMessage
  case object GetLastProcessedStream extends InMessage

  sealed trait OutMessage
  case class Failure(error: HTTP2Error) extends OutMessage
  case class Reserved(stream: Int) extends OutMessage
  case class IncomingAction(stream: Int, action: ReceiveAction) extends OutMessage
  case class OutgoingAction(stream: Int, action: SendAction) extends OutMessage
  case class LastProcessedStream(stream: Int) extends OutMessage

  def props(manager: => StreamManager): Props =
    Props(new StreamManagerActor(manager))
}

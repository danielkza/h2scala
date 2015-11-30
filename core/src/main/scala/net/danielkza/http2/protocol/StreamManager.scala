package net.danielkza.http2.protocol

import scala.collection.mutable
import scalaz.\/
import scalaz.syntax.either._

class StreamManager(val isClient: Boolean, val maxInStreams: Int, val ourMaxOutStreams: Int)
{
  import Frame._
  import HTTP2Error._
  import Http2Stream._
  import StreamManager._
  import Setting._

  private val controlStream = new ControlStream

  private var _lastProcessedId: Int = 0
  private var _lastOutId: Int = 0
  private var outPeerMaxStreams = Int.MaxValue

  private var openInStreams: Int = 0
  private var openOutStreams: Int = 0

  def lastProcessedId: Int = _lastProcessedId
  def lastOutId: Int = _lastOutId

  def markProcessed(streamId: Int): Unit = {
    if(checkInId(streamId) && controlStream.streams.contains(streamId) && streamId > _lastProcessedId)
      _lastProcessedId = streamId
  }

  def maxOutStreams: Int = Math.min(ourMaxOutStreams, outPeerMaxStreams)

  private def checkInId(id: Int): Boolean = {
    if(isClient) id != 0 && id % 2 == 0
    else         id % 2 != 0
  }

  private def checkOutId(id: Int): Boolean = {
    id != 0 && !checkInId(id)
  }

  private def closeUnusedInStreamsBelow(topId: Int): Unit = {
    var id = topId - 2

    while(id > 0) {
      if(controlStream.streams.contains(id))
        return

      controlStream.addStream(new DataStream(id, Closed, controlStream))
      id -= 2
    }
  }

  private def createInStream(id: Int, parent: Option[Int], dependency: Option[StreamDependency])
    : \/[HTTP2Error, DataStream] =
  {
    if(!checkInId(id) || controlStream.streams.contains(id))
      return InvalidStream().left

    if(parent.isEmpty && openInStreams >= maxInStreams)
      return RefusedStream("Available streams exhausted, must reconnect").left

    closeUnusedInStreamsBelow(id)
    val state = if(parent.isEmpty) {
      ReservedForRemote
    } else {
      openInStreams += 1
      Idle
    }

    val stream = new DataStream(id, state, controlStream)
    controlStream.addStream(stream)
    parent.foreach { p => stream.parentStream = p }

    stream.right
  }

  protected def adjustStreamWindow(delta: Int, stream: Http2Stream) = {
    if(delta >= 0)
      controlStream.incrementOutFlowWindow(delta)
    else
      controlStream.decrementOutFlowWindow(-delta)
  }

  def receive(frame: Frame): \/[HTTP2Error, ReceiveReply] = {
    def getStream(id: Int) =
      controlStream.streams.get(id).map(_.right).getOrElse(InvalidStream().left)

    def getOrCreateStream = frame match {
      case h: Headers =>
        createInStream(h.stream, None, h.streamDependency)
      case pp: PushPromise =>
        getStream(pp.stream).leftMap(_.withErrorMessage("Invalid parent stream in PUSH_PROMISE frame"))
      case f if f.stream == 0 =>
        controlStream.right
      case f =>
        getStream(f.stream)
    }

    for {
      stream <- getOrCreateStream
      newState <- stream.receive(frame)
      action <- frame match {
        case pp: PushPromise =>
          createInStream(pp.promisedStream, Some(pp.stream), None).map(_ => Continue(pp))
        case Priority(id, StreamDependency(exclusive, parent, weight)) if checkInId(id) =>
          stream match {
            case ds: DataStream =>
              ds.parentStream = parent
              ds.inWeight = weight
              // TODO: Handle exclusive
              Skip.right
            case _ =>
              InvalidStream("Priority cannot be send to control stream").left
          }
        case _: Priority =>
          InvalidStream("Invalid Priority stream").left
        case WindowUpdate(id, increment) =>
          stream.incrementOutFlowWindow(increment)
          Skip.right
        case _: ResetStream =>
          Stop.right
        case g: GoAway =>
          closeAll()
          Finish(g).right
        case s @ Settings(ExtractInitialWindowSize(window), _) =>
          val delta = window - controlStream.initialOutFlowWindow
          adjustStreamWindow(delta, controlStream)
          controlStream.streams.foreach {
            case (_, adjStream) if stream.state == Open || stream.state == HalfClosedByLocal =>
              adjustStreamWindow(delta, adjStream)
            case _ =>
          }
          controlStream.initialOutFlowWindow = window
          Skip.right
        case s @ Settings(ExtractMaxConcurrentStreams(numStreams), _) =>
          outPeerMaxStreams = numStreams
          Skip.right
        case s: Settings =>
          Skip.right
        case f if stream.isClosed =>
          Finish(f).right
        case f =>
          Continue(f).right
      }
    } yield {
      stream.state = newState
      ReceiveReply(stream, action)
    }
  }

  private def allocateOut(f: Int => DataStream): Option[Http2Stream] = {
    // The stream ID will wrap back to negative if it goes past 2^31, which is also the maximum acceptable value.
    // In this case, there's nothing to do and we just fail the allocation, and the peer should open a new connection
    // if desired (RFC-7540, Section 5.1.1)
    val newId = lastOutId + 2
    if(newId <= 0)
      None
    else {
      val stream = f(newId)
      controlStream.addStream(stream)
      _lastOutId = newId

      Some(stream)
    }
  }

  def reserveOut(): Option[Http2Stream] = {
    allocateOut { newId =>
      new DataStream(newId, ReservedForLocal, controlStream)
    }
  }

  private def checkPromisedStreamOut(id: Int): Boolean = {
    checkOutId(id) && controlStream.streams.get(id).exists { stream =>
      stream.state == ReservedForLocal
    }
  }

  def send(frame: Frame): \/[HTTP2Error, SendReply] = {
    for {
      stream <- controlStream.streams.get(frame.stream).map(_.right).getOrElse(InvalidStream().left)
      newState <- stream.send(frame)
      action <- frame match {
        case pp: PushPromise if !checkPromisedStreamOut(pp.promisedStream) =>
          // At this point the frame sender already has to have allocated the stream for the PushPromise. So we just
          // check if that is the case, without creating anything
          InvalidStream("Invalid promised stream in PUSH_PROMISE frame").left
        case w @ WindowUpdate(_, increment) =>
          stream.incrementInFlowWindow(increment)
          Continue(w).right
        case p @ Priority(id, StreamDependency(exclusive, parent, weight)) if checkOutId(id) =>
          stream.parentStream = parent
          stream.outWeight = weight
          // TODO: Handle exclusive
          Continue(p).right
        case _: Priority =>
          InvalidStream("Invalid stream in PRIORITY frame").left
        case rst: ResetStream =>
          Finish(rst).right
        case d: Data =>
          Delay(stream.acceptOutData(d)).right
        case g: GoAway =>
          closeAll()
          Finish(g).right
        case s @ Settings(ExtractInitialWindowSize(window), _) =>
          controlStream.initialOutFlowWindow = window
          Continue(s).right
        case s @ Settings(ExtractMaxConcurrentStreams(numStreams), _) if numStreams > maxInStreams =>
          SettingsError("Maximum in streams exceeds configuratin").left
        case f if stream.isClosed =>
          Finish(f).right
        case f =>
          Continue(f).right
      }
    } yield {
      stream.state = newState
      SendReply(stream, action)
    }
  }

  private def closeStream(stream: DataStream): Boolean = {
    if(!stream.isClosed) {
      val oldState = stream.state
      stream.close()

      if(checkInId(stream.id) && (oldState == HalfClosedByLocal || oldState == Open))
        openInStreams -= 1
      else if(checkOutId(stream.id) && (oldState == HalfClosedByRemote || oldState == Open))
        openOutStreams -= 1

      true
    } else {
      false
    }
  }

  private[http2] def closeAll() = controlStream.streams.values.foreach(closeStream)

  def close(streamId: Int): \/[HTTP2Error, Unit] = {
    controlStream.streams.get(streamId).map { stream =>
      if(!closeStream(stream))
        StreamClosedError().left
      else
        ().right
    } getOrElse InvalidStream().left
  }
}

object StreamManager {
  import Http2Stream._

  case class ReceiveReply(stream: Http2Stream, action: ReceiveAction)
  case class SendReply(stream: Http2Stream, action: SendAction)
}

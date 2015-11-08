package net.danielkza.http2.protocol.coders

import scala.util.Try
import scalaz._
import scalaz.std.list._
import scalaz.syntax.either._
import scalaz.syntax.traverse._
import akka.util.{ByteStringBuilder, ByteString}
import net.danielkza.http2.Coder
import net.danielkza.http2.protocol.{HTTP2Error, Frame}

class FrameCoder(targetStream: Int) extends Coder[Frame] {
  import Frame._
  import Frame.Flags._
  import IntCoder._
  import HTTP2Error._
  
  override final type Error = HTTP2Error
  
  protected def decodeStreamDependency: DecodeStateT[StreamDependency] = {
    for {
      stream <- int.decodeS
      weight <- byte.decodeS
    } yield {
      val exclusive = (stream >>> 31) != 0
      val streamNum = stream & 0x7FFFFFFF
      StreamDependency(exclusive, streamNum, (weight & 0xFF) + 1)
    }
  }
  
  protected def decodeUnpaddedBytes(length: Int): DecodeStateT[ByteString] = {
    takeS(length)
  }
  
  protected def decodeBytes(length: Int, padded: Boolean): DecodeStateT[(ByteString, Option[ByteString])] = {
    val SM = stateMonad[ByteString]; import SM._
    
    if(!padded) {
      decodeUnpaddedBytes(length).map(bs => bs -> None)
    } else {
      for {
        paddingLen <- if(padded) byte.decodeS
                      else pure(0: Byte)
        dataLen = length - paddingLen - 1
        _ <- ensureS(InvalidPadding) { dataLen > paddingLen }
        data <- takeS(dataLen)
        padding <- takeS(paddingLen)
        _ <- ensureS(InvalidFrameSize) { data.length == dataLen && padding.length == paddingLen }
      } yield data -> (Some(padding): Option[ByteString])
    }
  }
  
  protected def decodeData(length: Int, padded: Boolean, endStream: Boolean): DecodeStateT[Data] = {
    decodeBytes(length, padded).map { case (content, padding) => Data(content, endStream, padding) }
  }
  
  protected def decodeHeaders(length: Int, padded: Boolean, streamDependency: Boolean, endStream: Boolean,
                            endHeaders: Boolean): DecodeStateT[Headers] = {
    val SM = stateMonad[ByteString]
    
    for {
      bytes <- decodeBytes(length, padded)
      (content, padding) = bytes
      headers <- for {
        _ <- SM.put(content)
        streamDependency <- if(streamDependency) decodeStreamDependency.map(Some(_))
                            else SM.pure(None)
        data <- SM.get
      } yield Headers(streamDependency, data, endStream = endStream, endHeaders = endHeaders, padding = padding)
    } yield headers
  }
  
  protected def decodePriority: DecodeStateT[Priority] = {
    decodeStreamDependency.map(Priority)
  }
  
  protected def decodeResetStream: DecodeStateT[ResetStream] = {
    int.decodeS.map(ResetStream)
  }
  
  protected def decodeSingleSetting: DecodeStateT[(Short, Int)] = {
    for {
      identifier <- short.decodeS
      value <- int.decodeS
    } yield identifier -> value
  }

  protected def decodeSettings(num: Int, ack: Boolean): DecodeStateT[Settings] = {
    stateMonad[ByteString].replicateM(num, decodeSingleSetting).map(Settings(_, ack))
  }

  protected def decodePushPromise(length: Int, padded: Boolean, endHeaders: Boolean): DecodeStateT[PushPromise] = {
    val SM = stateMonad[ByteString]; import SM._
    
    for {
      bytes <- decodeBytes(length, padded)
      (content, padding) = bytes
      rem <- get
      _ <- put(content)
      stream <- int.decodeS
      _ <- ensureS(InvalidStream) { stream > 0 && stream % 2 == 0 }
      data <- get
      _ <- put(rem)
    } yield PushPromise(stream, data, endHeaders, padding)
  }

  protected def decodePing(ack: Boolean): DecodeStateT[Ping] = {
    for {
      content <- decodeUnpaddedBytes(8)
    } yield Ping(content, ack)
  }

  protected def decodeGoAway(length: Int): DecodeStateT[GoAway] = {
    for {
      stream <- int.decodeS
      _ <- ensureS(InvalidStream) { stream >= 0 }
      errorCode <- int.decodeS
      debugData <- takeS(length - 8)
      _ <- ensureS(InvalidFrameSize) { debugData.length == length - 8 }
    } yield GoAway(stream, errorCode, debugData)
  }

  protected def decodeWindowUpdate: DecodeStateT[WindowUpdate] = {  
    for {
      window <- int.decodeS
      windowVal = window & 0x7FFFFFFF
      _ <- ensureS(InvalidWindowUpdate) { windowVal != 0 } 
    } yield WindowUpdate(windowVal)
  }
  
  protected def decodePassthrough(tpe: Byte, length: Int, flags: Byte) : DecodeStateT[Unknown] = {
    for {
      content <- decodeUnpaddedBytes(length)
    } yield Unknown(tpe, flags, content)
  }
  
  protected def decodeContinuation(length: Int, endHeaders: Boolean): DecodeStateT[Continuation] = {  
    decodeUnpaddedBytes(length).map(Continuation(_, endHeaders))
  }
  
  protected def checkTargetStream(stream: Int) =
    stream == targetStream
  
  protected def checkStream[S](stream: Int, tpe: Byte) = {
    import Frame.Type._
    ensureS[S](InvalidStream) {
      if(stream != 0 && (tpe == SETTINGS.id || tpe == PING.id || tpe == GOAWAY.id))
        false
      else if(stream == 0 && (tpe == DATA.id || tpe == HEADERS.id || tpe == RST_STREAM.id || tpe == PRIORITY.id ||
                              tpe == CONTINUATION.id))
        false
      else
        checkTargetStream(stream)
    }
  }
  
  def payloadDecoder(tpe: Byte, length: Int, flags: Byte, stream: Int): \/[HTTP2Error, DecodeStateT[Frame]] = {
    def err = HTTP2Error.InvalidFrameSize.left
    
    val maybeHandler = Try(Frame.Type(tpe)).map { 
      case Type.DATA =>
        val padded = (flags & DATA.PADDED) != 0
        val endStream = (flags & DATA.END_STREAM) != 0
        if (padded && length < 1) err
        else decodeData(length, padded, endStream).right
      
      case Type.HEADERS =>
        val padded = (flags & HEADERS.PADDED) != 0
        val priority = (flags & HEADERS.PRIORITY) != 0
        val endStream = (flags & HEADERS.END_STREAM) != 0
        val endHeaders = (flags & HEADERS.END_HEADERS) != 0
        
        if(padded && priority && length < 6) err
        else if(priority && length < 5) err
        else if(padded && length < 1) err
        else decodeHeaders(length, padded, priority, endStream, endHeaders).right
      
      case Type.PRIORITY =>
        if (length != 5) err
        else decodePriority.right
      
      case Type.RST_STREAM =>
        if (length != 4) err
        else decodeResetStream.right
      
      case Type.SETTINGS =>
        if (length > 0 && (flags & SETTINGS.ACK) != 0) err
        else if (length % 6 != 0) err
        else decodeSettings(length / 6, (flags & SETTINGS.ACK) != 0).right
      
      case Type.PUSH_PROMISE =>
        val padded = (flags & PUSH_PROMISE.PADDED) != 0
        if (padded && length < 5) err
        else if(length < 4) err
        else decodePushPromise(length, padded, (flags & PUSH_PROMISE.END_HEADERS) != 0).right
      
      case Type.PING =>
        if (length != 8) err
        else decodePing((flags & PING.ACK) != 0).right
      
      case Type.GOAWAY =>
        if (length < 8) err
        else decodeGoAway(length).right
        
      case Type.WINDOW_UPDATE =>
        if (length != 4) err
        else decodeWindowUpdate.right
        
      case Type.CONTINUATION =>
        decodeContinuation(length, (flags & HEADERS.END_HEADERS) != 0).right
    }.recover { case e: NoSuchElementException =>
      decodePassthrough(tpe, length, flags).right[HTTP2Error]
    }.get
    
    // Convert from an invariant StateT of a subtype of Frame to one for Frame
    maybeHandler.map { handler =>
      checkStream(stream, tpe).flatMap(_ => handler.map(f => f: Frame))
    }
  }

  override def decode(bs: ByteString): \/[HTTP2Error, (Frame, Int)] = {
    decodeS.run(bs).map { case (rem, frame) => (frame, bs.length - rem.length) }
  }
  
  def decodeHeader: DecodeStateT[(DecodeStateT[Frame], Int)] = {
    val SMT = StateT.StateMonadTrans[ByteString]; import SMT._

    for {
      length <- int24.decodeS
      tpe <- byte.decodeS
      flags <- byte.decodeS
      stream <- int.decodeS
      handler <- liftMU(payloadDecoder(tpe, length, flags, stream))
    } yield handler -> length
  }

  override def decodeS: DecodeStateT[Frame] = {
    val SM = stateMonad[ByteString]; import SM._

    for {
      partialResult <- decodeHeader
      (handler, remLength) = partialResult
      remInput <- get
      _ <- ensureS(IncompleteInput(remInput.length, remLength)) { remInput.length < remLength }
      result <- handler
    } yield result
  }
  
  protected def encodeStreamDependency(streamDependency: StreamDependency): EncodeState = {
    val exclusiveBit = if(streamDependency.exclusive) 0x80000000 else 0
    for {
      _ <- int.encodeS(streamDependency.stream | exclusiveBit)
      _ <- byte.encodeS((streamDependency.weight - 1).toByte)
    } yield ()
  }
  
  protected def encodeBytes(padding: Option[ByteString])(f: EncodeState): EncodeState = {    
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    padding.map { padding =>       
      for {
        _ <- ensureS(InvalidPadding) { padding.length < 256 }
        _ <- byte.encodeS(padding.length.toByte)
        _ <- f
        _ <- modify { _ ++= padding }
      } yield ()
    }.getOrElse {
      f
    }
  }
  
  protected def encodeData(data: Data): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    encodeBytes(data.padding) { modify { _ ++= data.data } }
  }
  
  protected def encodeHeaders(headers: Headers): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    encodeBytes(headers.padding) {
      for {
        _ <- headers.streamDependency.map(encodeStreamDependency).getOrElse(point(()))
        _ <- modify { _ ++= headers.headerFragment }
      } yield ()
    }
  }
  
  protected def encodePriority(priority: Priority): EncodeState = {
    encodeStreamDependency(priority.streamDependency)
  }
  
  protected def encodeResetStream(resetStream: ResetStream): EncodeState = {
    int.encodeS(resetStream.errorCode)
  }
  
  protected def encodeSettings(settings: Settings): EncodeState = {
    type S[T] = StateTES[T, Error, ByteStringBuilder]
    settings.settings.traverse_[S] { case (identifier, value) =>
      for {
        _ <- short.encodeS(identifier)
        _ <- int.encodeS(value)
      } yield ()
    }
  }
  
  protected def encodePushPromise(pushPromise: PushPromise): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._

    encodeBytes(pushPromise.padding) {
      for {
        _ <- ensureS(InvalidStream) { pushPromise.stream >= 0 }
        _ <- int.encodeS(pushPromise.stream)
        _ <- modify { _ ++= pushPromise.headerFragment }
      } yield ()
    }
  }

  protected def encodePing(ping: Ping): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    for {
      _ <- ensureS(InvalidFrameSize) { ping.data.length == 8 }
      _ <- encodeBytes(None) { modify { _ ++= ping.data } }
    } yield ()
  }

  protected def encodeGoAway(goAway: GoAway): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    for {
      _ <- ensureS(InvalidStream) { goAway.lastStream >= 0 }
      _ <- int.encodeS(goAway.lastStream)
      _ <- int.encodeS(goAway.errorCode)
      _ <- modify { _ ++= goAway.debugData }
    } yield ()
  }

  protected def encodeWindowUpdate(windowUpdate: WindowUpdate): EncodeState = {  
    int.encodeS(windowUpdate.windowIncrement & 0x7FFFFFFF)
  }
  
  protected def encodeContinuation(continuation: Continuation): EncodeState = {  
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    encodeBytes(None) { modify { _ ++= continuation.headerFragment } }
  }
  
  
  protected def encodePassthrough(unknown: Unknown): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    modify { _ ++= unknown.payload }
  }  
    
  override def encode(frame: Frame, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
    encodeS(frame).eval(stream)
  }
  
  override def encodeS(frame: Frame): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._

    for {
      _ <- checkStream(targetStream, frame.tpe)
      buffer <- get
      _ <- put(ByteString.newBuilder)
      payload <- get
      _ <- frame match {
        case f: Data         => encodeData(f)
        case f: Headers      => encodeHeaders(f)
        case f: Priority     => encodePriority(f)
        case f: ResetStream  => encodeResetStream(f)
        case f: Settings     => encodeSettings(f)
        case f: PushPromise  => encodePushPromise(f)
        case f: Ping         => encodePing(f)
        case f: GoAway       => encodeGoAway(f)
        case f: WindowUpdate => encodeWindowUpdate(f)
        case f: Continuation => encodeContinuation(f)
        case f: Unknown      => encodePassthrough(f)
      }
      _ <- put(buffer)
      _ <- int24.encodeS(payload.length)
      _ <- byte.encodeS(frame.tpe)
      _ <- byte.encodeS(frame.flags)
      _ <- int.encodeS(targetStream)
      _ <- modify { _ ++= payload.result() }
    } yield ()
  }
}

object FrameCoder {
  sealed trait PartialDecodeResult
  object PartialDecodeResult {
    case class MoreData(length: Int) extends PartialDecodeResult
    case class Result(result: \/[HTTP2Error, Frame]) extends PartialDecodeResult
  }
}

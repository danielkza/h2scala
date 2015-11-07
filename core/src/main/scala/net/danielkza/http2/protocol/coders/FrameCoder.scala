package net.danielkza.http2.protocol.coders

import scala.util.Try
import scalaz._
import scalaz.std.list._
import scalaz.syntax.either._
import akka.util.{ByteStringBuilder, ByteString}
import net.danielkza.http2.Coder
import net.danielkza.http2.protocol.{HTTP2Error, Frame}

class FrameCoder extends Coder[Frame] {
  import Frame._
  import Frame.Flags._
  import IntCoder._
  import HTTP2Error._
  
  override final type Error = HTTP2Error
  
  private def decodeStreamDependency: DecodeStateT[StreamDependency] = {
    for {
      stream <- int.decodeS
      weight <- byte.decodeS
    } yield {
      val exclusive = stream < 0
      val streamNum = stream & 0x8FFFFFFF
      StreamDependency(exclusive, streamNum, weight)
    }
  }
  
  private def decodeBytes(length: Int, padded: Boolean): DecodeStateT[ByteString] = {
    val SM = stateMonad[ByteString]; import SM._
    
    for {
      paddingLen <- if(padded) byte.decodeS
                    else pure(0: Byte)
      data <- takeS(length - paddingLen)
      padding <- takeS(paddingLen)
      _ <- ensureS(InvalidFrameSize) { data.length == length && padding.length == paddingLen }
    } yield data
  }
  
  private def decodeData(length: Int, padded: Boolean, endStream: Boolean): DecodeStateT[Data] = {
    decodeBytes(length, padded).map(Data(_, endStream))
  }
  
  private def decodeHeaders(length: Int, padded: Boolean, streamDependency: Boolean, endStream: Boolean,
                            endHeaders: Boolean): DecodeStateT[Headers] = {
    val sdSM = stateMonad[Option[StreamDependency]]
    val bsSM = stateMonad[ByteString]
    
    for {
      content <- decodeBytes(length, padded)
      headers <- for {
        _ <- bsSM.put(content)
        streamDependency <- if(streamDependency) decodeStreamDependency.map(Some(_))
                            else bsSM.pure(None)
        data <- bsSM.get
      } yield Headers(streamDependency, data, endStream = endStream, endHeaders = endHeaders)
    } yield headers
  }
  
  private def decodePriority: DecodeStateT[Priority] = {
    decodeStreamDependency.map(Priority)
  }
  
  private def decodeResetStream: DecodeStateT[ResetStream] = {
    int.decodeS.map(ResetStream)
  }
  
  private def decodeSingleSetting: DecodeStateT[(Short, Int)] = {
    for {
      identifier <- short.decodeS
      value <- int.decodeS
    } yield identifier -> value
  }

  private def decodeSettings(num: Int, ack: Boolean): DecodeStateT[Settings] = {
    stateMonad[ByteString].replicateM(num, decodeSingleSetting).map(Settings(_, ack))
  }

  private def decodePushPromise(length: Int, padded: Boolean, endHeaders: Boolean): DecodeStateT[PushPromise] = {
    for {
      content <- decodeBytes(length, padded)
      stream <- int.decodeS
      _ <- ensureS(InvalidStream) { stream >= 0 }
    } yield PushPromise(stream, content, endHeaders)
  }

  private def decodePing(ack: Boolean): DecodeStateT[Ping] = {
    long.decodeS.map(Ping(_, ack))
  }

  private def decodeGoAway(length: Int): DecodeStateT[GoAway] = {
    for {
      stream <- int.decodeS
      _ <- ensureS(InvalidStream) { stream >= 0 }
      errorCode <- int.decodeS
      debugData <- takeS(length - 8)
      _ <- ensureS(InvalidFrameSize) { debugData.length != length - 8 }
    } yield GoAway(stream, errorCode, debugData)
  }

  private def decodeWindowUpdate: DecodeStateT[WindowUpdate] = {  
    for {
      window <- int.decodeS
      _ <- ensureS(InvalidWindowUpdate) { window <= 0 } 
    } yield WindowUpdate(window)
  }
  
  private def decodePassthrough(tpe: Byte, length: Int, flags: Byte) : DecodeStateT[Unknown] = {
    for {
      payload <- decodeBytes(length, padded = false)
    } yield Unknown(tpe, flags, payload)
  }

  def payloadDecoder(tpe: Byte, length: Int, flags: Byte): \/[HTTP2Error, DecodeStateT[Frame]] = {
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
        if (length % 6 != 0) err
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
    }.recover { case e: NoSuchElementException =>
      decodePassthrough(tpe, length, flags).right
    }.get
    
    // Convert from an invariant StateT of a subtype of Frame to one for Frame
    maybeHandler.map { handler => handler.map(_.asInstanceOf[Frame]) }
  }

  override def decode(bs: ByteString): \/[HTTP2Error, (Frame, Int)] = {
    decodeS.run(bs).flatMap {
      case (rem, frame) if rem.isEmpty => -\/(InvalidFrameSize)
      case (_, frame) => \/-(frame, bs.length)
    }
  }
  
  def partialDecodeS: DecodeStateT[DecodeStateT[Frame]] = {
    val SMT = StateT.StateMonadTrans[ByteString]; import SMT._

    for {
      length <- int24.decodeS
      tpe <- byte.decodeS
      flags <- byte.decodeS
      stream <- int.decodeS
      handler <- liftMU(payloadDecoder(tpe, length, flags))
    } yield handler
  }
  
  override def decodeS: DecodeStateT[Frame] = {
    for {
      handler <- partialDecodeS
      result <- handler
    } yield result
  }
  
  private def encodeStreamDependency(streamDependency: StreamDependency): EncodeState = {
    val exclusiveBit = if(streamDependency.exclusive) 0x80000000 else 0
    for {
      _ <- int.encodeS(streamDependency.stream | exclusiveBit)
      _ <- byte.encodeS(streamDependency.weight)
    } yield ()
  }
  
  private def encodeBytes(padding: Option[ByteString])(f: EncodeState): EncodeState = {    
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
  
  private def encodeData(data: Data, padding: Option[ByteString] = None): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    encodeBytes(padding) { modify { _ ++= data.data } }
  }
  
  private def encodeHeaders(headers: Headers, padding: Option[ByteString] = None): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    encodeBytes(padding) {
      for {
        _ <- headers.streamDependency.map(encodeStreamDependency).getOrElse(point(()))
        _ <- modify { _ ++= headers.headerFragment }
      } yield ()
    }
  }
  
  private def encodePriority(priority: Priority): EncodeState = {
    encodeStreamDependency(priority.streamDependency)
  }
  
  private def encodeResetStream(resetStream: ResetStream): EncodeState = {
    int.encodeS(resetStream.errorCode)
  }
  
  private def encodeSettings(settings: Settings): EncodeState = {
    //val SM = StateT.stateMonad[ByteStringBuilder]
    val T = Traverse[List]
    T.traverse_[StateTES[?, Error, ByteStringBuilder], (Short, Int), Unit](settings.settings) { case (identifier, value) =>
      for {
        _ <- short.encodeS(identifier)
        _ <- int.encodeS(value)
      } yield ()
    }
  }
  
  private def encodePushPromise(pushPromise: PushPromise, padding: Option[ByteString] = None): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._

    encodeBytes(padding) {
      for {
        _ <- ensureS(InvalidStream) { pushPromise.stream >= 0 }
        _ <- int.encodeS(pushPromise.stream)
        _ <- modify { _ ++= pushPromise.headerFragment }
      } yield ()
    }
  }

  private def encodePing(ping: Ping): EncodeState = {
    long.encodeS(ping.data)
  }

  private def encodeGoAway(goAway: GoAway): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    for {
      _ <- ensureS(InvalidStream) { goAway.lastStream >= 0 }
      _ <- int.encodeS(goAway.lastStream)
      _ <- int.encodeS(goAway.errorCode)
      _ <- modify { _ ++= goAway.debugData }
    } yield ()
  }

  private def encodeWindowUpdate(windowUpdate: WindowUpdate): EncodeState = {  
    int.encodeS(windowUpdate.windowIncrement)
  }
  
  private def encodePassthrough(unknown: Unknown): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    modify { _ ++= unknown.payload }
  }  
    
  override def encode(frame: Frame, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
    encodeS(frame).eval(stream)
  }
  
  override def encodeS(frame: Frame): EncodeState = {
    val SM = stateMonad[ByteStringBuilder]; import SM._
    
    val payloadEncoder = frame match {
      case f: Data         => encodeData(f)
      case f: Headers      => encodeHeaders(f)
      case f: Priority     => encodePriority(f)
      case f: ResetStream  => encodeResetStream(f)
      case f: Settings     => encodeSettings(f)
      case f: PushPromise  => encodePushPromise(f)
      case f: Ping         => encodePing(f)
      case f: GoAway       => encodeGoAway(f)
      case f: WindowUpdate => encodeWindowUpdate(f)
      case f: Unknown      => encodePassthrough(f)
    }
    
    for {
      stream <- get
      _ <- put(ByteString.newBuilder)
      payload <- get
      _ <- payloadEncoder
      _ <- put(stream)
      _ <- int24.encodeS(payload.length)
      _ <- byte.encodeS(frame.tpe)
      _ <- byte.encodeS(frame.flags)
      _ <- int.encodeS(0) // TODO: encode stream id
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

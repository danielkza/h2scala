package net.danielkza.http2.protocol

import akka.util.ByteString

sealed trait Frame {
  def tpe: Byte
  def flags: Byte
  def stream: Int
  def withFlags(flags: Byte): Frame
}

object Frame {
  final val HEADER_LENGTH = 9
  final val DEFAULT_MAX_FRAME_SIZE = 16384

  object Types {
    final val DATA: Byte          = 0x0
    final val HEADERS: Byte       = 0x1
    final val PRIORITY: Byte      = 0x2
    final val RST_STREAM: Byte    = 0x3
    final val SETTINGS: Byte      = 0x4
    final val PUSH_PROMISE: Byte  = 0x5
    final val PING: Byte          = 0x6
    final val GOAWAY: Byte        = 0x7
    final val WINDOW_UPDATE: Byte = 0x8
    final val CONTINUATION: Byte  = 0x9
  }

  object Flags {
    object DATA {
      final val END_STREAM: Byte = 0x1
      final val PADDED: Byte     = 0x8
    }

    object HEADERS {
      final val END_STREAM: Byte  = 0x1
      final val END_HEADERS: Byte = 0x4
      final val PADDED: Byte      = 0x8
      final val PRIORITY: Byte    = 0x20
    }

    object PUSH_PROMISE {
      final val END_HEADERS: Byte = 0x4
      final val PADDED: Byte      = 0x8
    }

    object PING {
      final val ACK: Byte = 0x1
    }

    object SETTINGS {
      final val ACK: Byte = 0x1
    }
  }

  case class NonStandard(
    override val stream: Int,
    override val tpe: Byte,
    override val flags: Byte,
    payload: ByteString
  ) extends Frame {
    override def withFlags(flags: Byte): NonStandard = copy(flags = flags)
  }

  sealed abstract class Standard(override val tpe: Byte) extends Frame {
    def flags: Byte = 0
  }

  case class StreamDependency(exclusive: Boolean, stream: Int, weight: Int)

  case class Data(
    override val stream: Int,
    data: ByteString,
    endStream: Boolean = false,
    padding: Option[ByteString] = None
  ) extends Standard(Types.DATA) {
    override def flags: Byte = {
      var flags = if(endStream) Flags.DATA.END_STREAM else 0
      padding.foreach { _ => flags |= Flags.DATA.PADDED }
      flags.toByte
    }

    override def withFlags(flags: Byte): Data =
      copy(endStream = (flags & Flags.DATA.END_STREAM) != 0)
  }

  case class Headers(
    override val stream: Int,
    streamDependency: Option[StreamDependency],
    headerFragment: ByteString,
    endStream: Boolean = false,
    endHeaders: Boolean = false,
    padding: Option[ByteString] = None
  ) extends Standard(Types.HEADERS) {
    override def flags: Byte = {
      var flags = if(streamDependency.isDefined) Flags.HEADERS.PRIORITY else 0
      padding.foreach { _ => flags |= Flags.HEADERS.PADDED }
      if(endStream) flags |= Flags.HEADERS.END_STREAM
      if(endHeaders) flags |= Flags.HEADERS.END_HEADERS

      flags.toByte
    }

    override def withFlags(flags: Byte): Headers =
      copy(endStream = (flags & Flags.HEADERS.END_STREAM) != 0,
           endHeaders = (flags & Flags.HEADERS.END_HEADERS) != 0)
  }

  case class Priority(
    override val stream: Int,
    streamDependency: StreamDependency
  ) extends Standard(Types.PRIORITY) {
    override def withFlags(flags: Byte): Priority = this
  }

  case class ResetStream(
    override val stream: Int,
    errorCode: Int
  ) extends Standard(Types.RST_STREAM) {
    override def withFlags(flags: Byte): ResetStream = this
  }
  case class PushPromise(
    override val stream: Int,
    promisedStream: Int,
    headerFragment: ByteString,
    endHeaders: Boolean = false,
    padding: Option[ByteString] = None
  ) extends Standard(Types.PUSH_PROMISE) {
    override def flags: Byte = {
      var flags = if(endHeaders) Flags.PUSH_PROMISE.END_HEADERS else 0
      padding.foreach { _ => flags |= Flags.PUSH_PROMISE.PADDED }
      flags.toByte
    }

    override def withFlags(flags: Byte): PushPromise =
      copy(endHeaders = (flags & Flags.PUSH_PROMISE.END_HEADERS) != 0)
  }

  case class Ping(
    data: ByteString,
    ack: Boolean = false
  ) extends Standard(Types.PING) {
    override def stream: Int = 0

    override def flags: Byte =
      if(ack) Flags.PING.ACK else 0

    override def withFlags(flags: Byte): Ping =
      copy(ack = (flags & Flags.PING.ACK) != 0)
  }

  case class Settings(
    settings: List[Setting],
    ack: Boolean = false
  ) extends Standard(Types.SETTINGS) {
    override def stream: Int = 0

    override def flags: Byte =
      if(ack) Flags.SETTINGS.ACK else 0

    override def withFlags(flags: Byte): Settings =
      copy(ack = (flags & Flags.SETTINGS.ACK) != 0)
  }

  case class GoAway(
    lastStream: Int,
    errorCode: Int,
    debugData: ByteString
  ) extends Standard(Types.GOAWAY) {
    override def stream: Int = 0

    override def withFlags(flags: Byte): GoAway = this
  }
  object GoAway {
    def apply(lastStream: Int, error: HTTP2Error = HTTP2Error.NoError): GoAway = {
      GoAway(lastStream, error.code, error.debugData.getOrElse(ByteString.empty))
    }
  }

  case class WindowUpdate(
    override val stream: Int,
    windowIncrement: Int
  ) extends Standard(Types.WINDOW_UPDATE) {
    override def withFlags(flags: Byte): WindowUpdate= this
  }

  case class Continuation(
    override val stream: Int,
    headerFragment: ByteString,
    endHeaders: Boolean = false
  ) extends Standard(Types.CONTINUATION) {
    override def withFlags(flags: Byte): Continuation = this
  }

}

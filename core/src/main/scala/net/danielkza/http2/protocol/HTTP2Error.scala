package net.danielkza.http2.protocol

sealed trait HTTP2Error {
  def code: Int = 0
}

sealed abstract class KnownHTTP2Error(override val code: Int)
  extends HTTP2Error

object HTTP2Error {
  object Values {
    final val NO_ERROR            = 0x0
    final val PROTOCOL_ERROR      = 0x1
    final val INTERNAL_ERROR      = 0x2
    final val FLOW_CONTROL_ERROR  = 0x3
    final val SETTINGS_TIMEOUT    = 0x4
    final val STREAM_CLOSED       = 0x5
    final val FRAME_SIZE_ERROR    = 0x6
    final val REFUSED_STREAM      = 0x7
    final val CANCEL              = 0x8
    final val COMPRESSION_ERROR   = 0x9
    final val CONNECT_ERROR       = 0xa
    final val ENHANCE_YOUR_CALM   = 0xb
    final val INADEQUATE_SECURITY = 0xc
    final val HTTP_1_1_REQUIRED   = 0xd
  }

  case class IncompleteInput(received: Int, expected: Int) extends KnownHTTP2Error(Values.FRAME_SIZE_ERROR)
  case object InvalidStream extends KnownHTTP2Error(Values.PROTOCOL_ERROR)
  case object InvalidFrameSize extends KnownHTTP2Error(Values.FRAME_SIZE_ERROR)
  case object InvalidPadding extends KnownHTTP2Error(Values.PROTOCOL_ERROR)
  case object InvalidWindowUpdate extends KnownHTTP2Error(Values.PROTOCOL_ERROR)
}

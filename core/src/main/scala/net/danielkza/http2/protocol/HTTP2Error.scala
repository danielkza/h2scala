package net.danielkza.http2.protocol

sealed trait HTTP2Error {
  def code: Int = 0
}

sealed abstract class KnownHTTP2Error(override val code: Int)
  extends HTTP2Error

object HTTP2Error {
  object Values {
    val NO_ERROR            = 0x0
    val PROTOCOL_ERROR      = 0x1
    val INTERNAL_ERROR      = 0x2
    val FLOW_CONTROL_ERROR  = 0x3
    val SETTINGS_TIMEOUT    = 0x4
    val STREAM_CLOSED       = 0x5
    val FRAME_SIZE_ERROR    = 0x6
    val REFUSED_STREAM      = 0x7
    val CANCEL              = 0x8
    val COMPRESSION_ERROR   = 0x9
    val CONNECT_ERROR       = 0xa
    val ENHANCE_YOUR_CALM   = 0xb
    val INADEQUATE_SECURITY = 0xc
    val HTTP_1_1_REQUIRED   = 0xd
  }

  case class IncompleteInput(received: Int, expected: Int) extends HTTP2Error
  case object InvalidStream extends KnownHTTP2Error(Values.PROTOCOL_ERROR)
  case object InvalidFrameSize extends KnownHTTP2Error(Values.FRAME_SIZE_ERROR)
  case object InvalidPadding extends KnownHTTP2Error(Values.PROTOCOL_ERROR)
  case object InvalidWindowUpdate extends KnownHTTP2Error(Values.PROTOCOL_ERROR)
}

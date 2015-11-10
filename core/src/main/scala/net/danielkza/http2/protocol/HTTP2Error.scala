package net.danielkza.http2.protocol

import akka.util.ByteString

trait HTTP2Error {
  def code: Int
  def debugData: Option[ByteString]
}

object HTTP2Error {
  object Codes {
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
  import Codes._

  class Standard(override val code: Int, override val debugData: Option[ByteString] = None) extends HTTP2Error
  object Standard {
    def unapply(error: HTTP2Error): Option[(Int, Option[ByteString])] = {
      if(error.code >= PROTOCOL_ERROR && error.code <= HTTP_1_1_REQUIRED)
        Some(error.code, error.debugData)
      else
        None
    }
  }

  class InvalidStream(debugData: Option[ByteString] = None) extends Standard(PROTOCOL_ERROR)
  class InvalidFrameSize(debugData: Option[ByteString] = None) extends Standard(FRAME_SIZE_ERROR)
  class InvalidWindowUpdate(debugData: Option[ByteString] = None) extends Standard(PROTOCOL_ERROR)
  class InvalidPadding(debugData: Option[ByteString] = None) extends Standard(PROTOCOL_ERROR)
  class CompressionError(debugData: Option[ByteString] = None) extends Standard(COMPRESSION_ERROR)

  object NonStandard {
    def unapply(error: HTTP2Error): Option[(Int, Option[ByteString])] = {
      Standard.unapply(error) match {
        case Some(_) => None
        case None => Some(error.code, error.debugData)
      }
    }
  }
}

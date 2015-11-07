package net.danielkza.http2.protocol

sealed trait HTTP2Error

object HTTP2Error {
  case class IncompleteInput(received: Int, expected: Int) extends HTTP2Error
  case object InvalidStream extends HTTP2Error
  case object InvalidFrameSize extends HTTP2Error
  case object InvalidPadding extends HTTP2Error
  case object InvalidWindowUpdate extends HTTP2Error
}

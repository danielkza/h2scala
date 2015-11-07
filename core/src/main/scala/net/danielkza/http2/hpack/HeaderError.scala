package net.danielkza.http2.hpack

sealed trait HeaderError

object HeaderError {
  case class ExcessivePadding(pos: Int) extends HeaderError
  case class InvalidPadding(pos: Int) extends HeaderError
  case class UnknownCode(pos: Int) extends HeaderError
  case class IncompleteInput(received: Int, expected: Int) extends HeaderError
  case class EOSDecoded(pos: Int) extends HeaderError
  case object DynamicTableCapacityExceeded extends HeaderError
  case object ParseError extends HeaderError
  case class InvalidIndex(index: Int) extends HeaderError
}

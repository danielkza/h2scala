package net.danielkza.http2.hpack

import akka.util.ByteString

sealed trait HeaderRepr
sealed trait Incremental extends HeaderRepr

object HeaderRepr {
  case class Indexed(index: Int)
    extends HeaderRepr
  case class IncrementalLiteralWithIndexedName(keyIndex: Int, value: ByteString)
    extends Incremental
  case class IncrementalLiteral(key: ByteString, value: ByteString)
    extends Incremental
  case class LiteralWithIndexedName(keyIndex: Int, value: ByteString)
    extends HeaderRepr
  case class Literal(key: ByteString, value: ByteString)
    extends HeaderRepr
  case class NeverIndexedWithIndexedName(keyIndex: Int, value: ByteString)
    extends HeaderRepr
  case class NeverIndexed(key: ByteString, value: ByteString)
    extends HeaderRepr
  case class DynamicTableSizeUpdate(size: Int)
    extends HeaderRepr
}

package net.danielkza.http2.hpack.coders

import java.io.OutputStream

import scalaz.{-\/, \/}
import akka.util.{ByteStringBuilder, ByteString}

import shapeless._

import net.danielkza.http2.Coder
import net.danielkza.http2.util._
import net.danielkza.http2.hpack.{HeaderError, HeaderRepr}

class HeaderCoder(val compressionPredicate: ByteString => Boolean = HeaderCoder.compress.default)
  extends Coder[HeaderRepr]
{
  import HeaderRepr._
  import Coder.defineCompositeCoder
  
  override final type Error = HeaderError
  
  private final val indexedIndex = new IntCoder(7, prefix=1)
  private final val incrementalLiteralIndex = new IntCoder(6, prefix=1)
  private final val literalIndex = new IntCoder(4, prefix=0)
  private final val neverIndexedIndex = new IntCoder(4, prefix=1)
  private final val tableUpdateSize = new IntCoder(5, prefix=1)
  private final val literalString = new LiteralCoder(compressionPredicate)
  
  private final val indexed = defineCompositeCoder(Generic[Indexed])
    { indexedIndex :: HNil }
  
  private final val incrLiteralIdxName = defineCompositeCoder(Generic[IncrementalLiteralWithIndexedName])
    { incrementalLiteralIndex :: literalString :: HNil }
  
  private final val incrLiteral = defineCompositeCoder(Generic[HeaderRepr.IncrementalLiteral])
    { literalString :: literalString :: HNil }
  
  private final val literalIdxName = defineCompositeCoder(Generic[HeaderRepr.LiteralWithIndexedName])
    { literalIndex :: literalString :: HNil } 
  
  private final val literal = defineCompositeCoder(Generic[HeaderRepr.Literal])
    { literalString :: literalString :: HNil }
  
  private final val neverIndexedIdxName = defineCompositeCoder(Generic[HeaderRepr.NeverIndexedWithIndexedName])
    { neverIndexedIndex :: literalString :: HNil }
  
  private final val neverIndexed = defineCompositeCoder(Generic[HeaderRepr.NeverIndexed])
    { literalString :: literalString :: HNil }
  
  private final val dynamicTableSizeUpdate = defineCompositeCoder(Generic[DynamicTableSizeUpdate])
    { tableUpdateSize :: HNil }
  
  
  override def encode(value: HeaderRepr, stream: ByteStringBuilder): \/[HeaderError, Unit] = {
    value match {
      case h: Indexed                           => indexed.encode(h, stream)
      case h: IncrementalLiteral                => stream.putByte(bin_b"01000000"); incrLiteral.encode(h, stream)
      case h: IncrementalLiteralWithIndexedName => incrLiteralIdxName.encode(h, stream)
      case h: Literal                           => stream.putByte(bin_b"00000000"); literal.encode(h, stream)
      case h: LiteralWithIndexedName            => literalIdxName.encode(h, stream)
      case h: NeverIndexed                      => stream.putByte(bin_b"00010000"); neverIndexed.encode(h, stream)
      case h: NeverIndexedWithIndexedName       => neverIndexedIdxName.encode(h, stream)
      case h: DynamicTableSizeUpdate            => dynamicTableSizeUpdate.encode(h, stream)
      case _                                    => -\/(HeaderError.ParseError)
    }
  }
  
  override def decode(stream: ByteString): \/[HeaderError, (HeaderRepr, Int)] = {
    stream.head match {
      case bin"1-------" => indexed.decode(stream)
      case bin"01000000" => incrLiteral.decode(stream.drop(1)).map { case (v, l) => (v, l + 1) }
      case bin"01------" => incrLiteralIdxName.decode(stream)
      case bin"001-----" => dynamicTableSizeUpdate.decode(stream)
      case bin"00000000" => literal.decode(stream.drop(1)).map { case (v, l) => (v, l + 1) }
      case bin"0000----" => literalIdxName.decode(stream)
      case bin"00010000" => neverIndexed.decode(stream.drop(1)).map { case (v, l) => (v, l + 1) }
      case bin"0001----" => neverIndexedIdxName.decode(stream)
      case _           => -\/(HeaderError.ParseError)
    }
  }
}

object HeaderCoder {
  object compress {
    case class Threshold(minBytes: Int) extends (ByteString => Boolean) {
      @inline override def apply(value: ByteString): Boolean =
        value.length >= minBytes
    }
    
    final val Never = (value: ByteString) => false
    final val Always = (value: ByteString) => true
    
    final val default = Threshold(512)
  }
}

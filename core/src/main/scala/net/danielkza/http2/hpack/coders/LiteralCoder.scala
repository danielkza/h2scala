package net.danielkza.http2.hpack.coders

import scalaz.\/
import akka.util.{ByteStringBuilder, ByteString}
import net.danielkza.http2.util._
import net.danielkza.http2.Coder
import net.danielkza.http2.hpack.HeaderError

class LiteralCoder(val compressionPredicate: ByteString => Boolean) extends Coder[ByteString]  {
  override final type Error = HeaderError
  
  private val huffmanCoder = new CompressedBytesCoder
  private val plainCoder = new BytesCoder
  
  override def encode(value: ByteString): \/[HeaderError, ByteString] = {
    if(!compressionPredicate(value))
      plainCoder.encode(value)
    else
      huffmanCoder.encode(value)
  }
  
  override def encode(value: ByteString, stream: ByteStringBuilder): \/[HeaderError, Unit] = {
    if(!compressionPredicate(value))
      plainCoder.encode(value, stream)
    else
      huffmanCoder.encode(value, stream)
  }
  
  override def decode(bs: ByteString): \/[HeaderError, (ByteString, Int)] = {
    bs.head match {
      case bin"1-------" =>
        huffmanCoder.decode(bs)
      case _          =>
        plainCoder.decode(bs)
    }
  }
}

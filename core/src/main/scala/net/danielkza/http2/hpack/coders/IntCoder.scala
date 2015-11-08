package net.danielkza.http2.hpack.coders

import java.util.NoSuchElementException
import scalaz.\/
import scalaz.syntax.either._
import akka.util.{ByteString, ByteStringBuilder}
import net.danielkza.http2.Coder
import net.danielkza.http2.hpack.HeaderError

class IntCoder(elementSize: Int, prefix: Int = 0) extends Coder[Int] {
  override final type Error = HeaderError
  
  val elementMax = (1 << elementSize) - 1
  val elementMask = elementMax

  val prefixSize = 8 - elementSize
  val prefixMax = (1 << prefixSize) - 1
  val alignedPrefix = (prefix & prefixMax) << elementSize

  override def encode(value: Int, stream: ByteStringBuilder): \/[HeaderError, Unit] = {
    if(value < elementMax) {
      stream.putByte(((value & elementMax) | alignedPrefix).toByte)
    } else {
      stream.putByte((elementMax | alignedPrefix).toByte)

      var tmpValue = value - elementMax
      while(tmpValue >= 128) {
        stream.putByte(((tmpValue & 127) | 128).toByte)
        tmpValue >>>= 7
      }

      stream.putByte(tmpValue.toByte)
    }
    
    ().right
  }

  private def decode(stream: BufferedIterator[Byte]): \/[HeaderError, (Int, Int)] = try {
    val firstElm = stream.next & elementMask
    if(firstElm < elementMax)
      (firstElm.toInt, 1).right
    else {
      var bitPos: Int = 0
      var value: Int = elementMax
      var byte: Byte = 0
      do {
        byte = stream.next()
        value += (byte & 127) << bitPos
        bitPos += 7
      } while((byte & 128) == 128)
      (value, bitPos / 7 + 1).right
    }
  } catch { case e: NoSuchElementException =>
    HeaderError.ParseError.left
  }

  override def decode(bs: ByteString): \/[HeaderError, (Int, Int)] =
    decode(bs.iterator)
}

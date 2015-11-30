package net.danielkza.http2.util

import scala.language.experimental.macros
import java.io.{IOException, OutputStream}
import java.nio.ByteBuffer
import scalaz.\/
import akka.util.ByteString
import net.danielkza.http2.macros.{BitPatterns => BitPatternsMacros}

trait Implicits {
  implicit class BytePattern(sc: StringContext) {
    object bin extends {      
      def unapply(x: Byte): Boolean = macro BitPatternsMacros.binaryLiteralExtractorImpl[Byte]
      def unapply(x: Short): Boolean = macro BitPatternsMacros.binaryLiteralExtractorImpl[Short]
      def unapply(x: Int): Boolean = macro BitPatternsMacros.binaryLiteralExtractorImpl[Int]
      def unapply(x: Long): Boolean = macro BitPatternsMacros.binaryLiteralExtractorImpl[Long]
      
      def apply(args: Any*) = macro BitPatternsMacros.binaryLiteralImpl[Int]
    }
    
    def bin_b(args: Any*): Byte = macro BitPatternsMacros.binaryLiteralImpl[Byte]
    def bin_s(args: Any*): Short = macro BitPatternsMacros.binaryLiteralImpl[Short]
    def bin_i(args: Any*): Int = macro BitPatternsMacros.binaryLiteralImpl[Int]
    def bin_l(args: Any*): Long = macro BitPatternsMacros.binaryLiteralImpl[Long]
  }

  private def byteStringFromIntegers(str: String, groupSize: Int, radix: Int): ByteString = {
    val cleanStr = str.replaceAll("\\s", "")
    val builder = ByteString.newBuilder
    builder.sizeHint(cleanStr.length / groupSize)

    cleanStr.grouped(groupSize).map(s => Integer.parseUnsignedInt(s, radix)).foreach { byte =>
      builder.putByte(byte.toByte)
    }

    builder.result()
  }
  
  implicit class ByteStringConversion(sc: StringContext) {
    def u8_bs(args: Any*): ByteString =
      ByteString(sc.s(args: _*), "UTF-8")
    
    def bs(args: Any*): ByteString = u8_bs(args: _*)


    def bits_bs(args: Any*): ByteString =
      byteStringFromIntegers(sc.s(args: _*), groupSize=8, radix=2)

    def hex_bs(args: Any*): ByteString =
      byteStringFromIntegers(sc.s(args: _*), groupSize=2, radix=16)
  }
  
  implicit class StringByteStringOps(s: String) {
    def byteStringFromBits: ByteString = 
      byteStringFromIntegers(s, groupSize=8, radix=2)
    
    def byteStringFromHex: ByteString = 
      byteStringFromIntegers(s, groupSize=2, radix=16)
  }
  
  implicit class RichOutputStream(stream: OutputStream) {
    @throws[IOException] def write(buffer: ByteBuffer): Unit = {
      val pos = buffer.position()
      if(buffer.hasArray) {
        stream.write(buffer.array(), buffer.arrayOffset() + pos, buffer.limit() - pos)
      } else {
        while(buffer.hasRemaining) {
          stream.write(buffer.get())
        }
        
        buffer.position(pos)
      }
    }
    
    @throws[IOException] def write(string: ByteString): Unit = {
      string.asByteBuffers.foreach(write)
    }
  }

  implicit class DisjunctionWithThrow[E, V](self: \/[E, V])(implicit ev: E => Throwable) {
    def orThrow: V = self.leftMap { e => throw ev(e) }.merge
  }
}

object Implicits extends Implicits

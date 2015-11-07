package net.danielkza.http2.protocol.coders

import java.nio.ByteOrder
import scalaz.\/
import scalaz.syntax.either._
import akka.util.{ByteStringBuilder, ByteString}
import net.danielkza.http2.Coder
import net.danielkza.http2.protocol.HTTP2Error

abstract class IntCoder[T] extends Coder[T] {
  @inline def checkDecode(bs: ByteString)(f: ByteString => \/[HTTP2Error, (T, Int)]): \/[HTTP2Error, (T, Int)] = {
    try {
      f(bs)
    } catch { case e: IndexOutOfBoundsException =>
      HTTP2Error.IncompleteInput(-1, -1).left
    }
  } 
}

object IntCoder {
  object byte extends IntCoder[Byte] {
    override final type Error = HTTP2Error
      
    override final def decode(bs: ByteString) = checkDecode(bs) { bs =>
      (bs.head, 1).right
    }
    
    override final def encode(value: Byte, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
      stream.putByte(value)
      ().right
    }

    override final def encode(value: Byte): \/[HTTP2Error, ByteString] =
      ByteString(value).right
  }
  
  object short extends IntCoder[Short] {
    override final type Error = HTTP2Error
      
    override final def decode(bs: ByteString): \/[HTTP2Error, (Short, Int)] = checkDecode(bs) { bs =>
      ((
        (bs(0) & 0xFF) << 8 |
        (bs(1) & 0xFF) << 0
      ).toShort -> 2).right
    }
    
    override final def encode(value: Short, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
      stream.putShort(value)(ByteOrder.BIG_ENDIAN)
      ().right
    }

    override final def encode(value: Short): \/[HTTP2Error, ByteString] =
      ByteString((value >>> 8).toByte,
                 (value >>> 0).toByte).right
  }
  
  object int extends IntCoder[Int] {
    override final type Error = HTTP2Error
      
    override final def decode(bs: ByteString): \/[HTTP2Error, (Int, Int)] = checkDecode(bs) { bs =>
      ((
        (bs(0) & 0xFF) << 24 |
        (bs(1) & 0xFF) << 16 |
        (bs(2) & 0xFF) << 8 |
        (bs(3) & 0xFF) << 0
      ) -> 4).right
    }
    
    override final def encode(value: Int, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
      stream.putInt(value)(ByteOrder.BIG_ENDIAN)
      ().right
    }

    override final def encode(value: Int): \/[HTTP2Error, ByteString] =
      ByteString((value >>> 24).toByte,
                 (value >>> 16).toByte,
                 (value >>> 8).toByte,
                 (value >>> 0).toByte).right
  }
  
  object int24 extends IntCoder[Int] {
    override final type Error = HTTP2Error
  
    override final def decode(bs: ByteString): \/[HTTP2Error, (Int, Int)] = checkDecode(bs) { bs =>
      ((
        (bs(0) & 0xFF) << 16 |
        (bs(1) & 0xFF) << 8 |
        (bs(2) & 0xFF) << 0
      ) -> 3).right
    }
    
    override final def encode(value: Int, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
      stream.putByte((value >>> 16).toByte)
      stream.putByte((value >>> 8).toByte)
      stream.putByte((value >>> 0).toByte)
      ().right
    }

    override final def encode(value: Int): \/[HTTP2Error, ByteString] =
      ByteString((value >>> 16).toByte,
                 (value >>> 8).toByte,
                 (value >>> 0).toByte).right
  }
  
  object long extends IntCoder[Long] {
    override final type Error = HTTP2Error
    
    override final def decode(bs: ByteString): \/[HTTP2Error, (Long, Int)] = checkDecode(bs) { bs =>
      ((
        (bs(0) & 0xFFL) << 56 |
        (bs(1) & 0xFFL) << 48 |
        (bs(2) & 0xFFL) << 40 |
        (bs(3) & 0xFFL) << 32 |
        (bs(4) & 0xFFL) << 24 |
        (bs(5) & 0xFFL) << 16 |
        (bs(6) & 0xFFL) << 8 |
        (bs(7) & 0xFFL) << 0
      ) -> 8).right
    }
    
    override final def encode(value: Long, stream: ByteStringBuilder): \/[HTTP2Error, Unit] = {
      stream.putLong(value)(ByteOrder.BIG_ENDIAN)
      ().right
    }

    override final def encode(value: Long): \/[HTTP2Error, ByteString] =
      ByteString((value >>> 56).toByte,
                 (value >>> 48).toByte,
                 (value >>> 40).toByte,
                 (value >>> 32).toByte,
                 (value >>> 24).toByte,
                 (value >>> 16).toByte,
                 (value >>> 8).toByte,
                 (value >>> 0).toByte).right
  }

}

package net.danielkza.http2.hpack.coders

import scala.annotation.tailrec
import scalaz._
import scalaz.syntax.either._
import akka.util.{ByteString, ByteStringBuilder}
import net.danielkza.http2.Coder
import net.danielkza.http2.hpack.HeaderError

class CompressedBytesCoder extends Coder[ByteString] {
  final override type Error = HeaderError
  
  import CompressedBytesCoder._

  private def encodeInternal(value: ByteString, buffer: ByteStringBuilder): Int = {
    import HuffmanCoding.{Code, coding}
        
    var hold: Long = 0
    var outstandingBits = 0
    var length: Int = 0

    def encodeSymbol(symbol: Byte): Unit = {
      val Code(code, codeBits) = coding.encodingTable(symbol)
      hold |= (code & 0xFFFFFFFFL) << (32 - outstandingBits)
      outstandingBits += codeBits
      
      while(outstandingBits >= 8) {
        buffer += (hold >>> 56).toByte
        outstandingBits -= 8
        hold <<= 8
        
        length += 1
      }
    }
    
    value.foreach(byte => encodeSymbol(byte))
    
    if(outstandingBits > 0) {
      val lastByte = (hold >>> 56) | (0xFF >>> outstandingBits)
      buffer.putByte(lastByte.toByte)
      length += 1
    }
    
    length
  }

  override def encode(value: ByteString, stream: ByteStringBuilder): \/[HeaderError, Unit] = {
    val buffer = ByteString.newBuilder
    buffer.sizeHint((value.length * 2) / 3)
    
    val length = encodeInternal(value, buffer)
    lengthCoder.encode(length, stream).map { _ =>
      stream ++= buffer.result()
    }
  }

  override def encode(value: ByteString): \/[HeaderError, ByteString] = {
    val buffer = ByteString.newBuilder
    encode(value, buffer).map(_ => buffer.result())
  }

  private def validPadding(value: Int, bits: Int) = {
    if (bits == 0) true
    else (value.toByte >> (8 - bits)) == -1
  }
  
  private def decodeLiteral(iter: BufferedIterator[Byte], length: Int): \/[HeaderError, (ByteString, Int)] = {
    import HuffmanCoding.{EOS, coding, Symbol}
    import HeaderError._
    import coding.DecodingTable

    var outstandingBits = 0
    var hold: Long = 0
    var prefixBits: Int = 0
    var bytesRead = 0
    var error: HeaderError = null
    
    @inline def raiseError(f: (Int) => HeaderError): Short = {
      error = f(bytesRead)
      -1
    }

    @tailrec
    def decodeSymbol(currentTable: DecodingTable = coding.initialTable): Short = {
      if(outstandingBits >= 32)
        return raiseError(UnknownCode)

      if(bytesRead < length && (outstandingBits - prefixBits) < 8) {
        if(!iter.hasNext)
          return raiseError(IncompleteInput(_, length))
        else {
          hold |= (iter.next() & 0xFFL) << (56 - outstandingBits)
          bytesRead += 1
          outstandingBits += 8
        }
      }
      
      val nextByte = ((hold >>> (56 - prefixBits)) & 0xFF).toInt
      currentTable.lift(nextByte) match {
        case Some(Symbol(symbol, codeLen)) if outstandingBits >= codeLen =>
          outstandingBits -= codeLen
          hold <<= codeLen
          prefixBits = 0
          
          if(symbol == EOS)
            raiseError(EOSDecoded)
          else
            symbol          
        case _ if bytesRead == length =>
          if(outstandingBits > 7)
            raiseError(ExcessivePadding)
          else if(!validPadding(nextByte, outstandingBits))
            raiseError(InvalidPadding)
          else 
            EOS
        case _ =>
          prefixBits = 8 * (outstandingBits / 8) // observe truncation
          val prefix = (hold >>> 32).toInt & (-1 << (32 - prefixBits))
          coding.decodingTableForCode(prefix) match {
            case Some(nextTable) =>
              decodeSymbol(nextTable)
            case _ => raiseError(UnknownCode)
          }
      }
    }
    
    var res = new ByteStringBuilder
    res.sizeHint((length * 3) / 2)
  
    @tailrec
    def decode(): \/[HeaderError, (ByteString, Int)] = {
      decodeSymbol() match {
        case HuffmanCoding.EOS =>
          (res.result(), bytesRead).right
        case -1 =>
          -\/(error)
        case symbol =>
          res = res.putByte(symbol.toByte)
          decode()
      }
    }
    
    decode()
  }

  override def decode(bs: ByteString): \/[HeaderError, (ByteString, Int)] = {
    for {
      encodedBytesResult <- bytesCoder.decode(bs)
      (encodedBytes, numReadBytes) = encodedBytesResult
      decodeResult <- decodeLiteral(encodedBytes.iterator, encodedBytes.length)
    } yield (decodeResult._1, numReadBytes)
  }
}

object CompressedBytesCoder {
  val lengthCoder = new IntCoder(7, prefix=1)
  val bytesCoder = new BytesCoder(lengthPrefix=1)
}

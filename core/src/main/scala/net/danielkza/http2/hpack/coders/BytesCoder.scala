package net.danielkza.http2.hpack.coders

import java.io.OutputStream
import akka.util.{ByteStringBuilder, ByteString}
import scalaz.{\/, -\/, \/-}
import scalaz.syntax.either._

import net.danielkza.http2.Coder
import net.danielkza.http2.util._
import net.danielkza.http2.hpack.HeaderError

class BytesCoder(lengthPrefix: Int = 0) extends Coder[ByteString] {
  override final type Error = HeaderError
  
  val intCoder = new IntCoder(7, lengthPrefix)

  override def encode(value: ByteString, stream: ByteStringBuilder): \/[HeaderError, Unit] = {
    intCoder.encode(value.length, stream).map { _ =>
      stream ++= value
    }
  }
  
  override def decode(bs: ByteString): \/[HeaderError, (ByteString, Int)] = {
    for {
      lengthResult <- intCoder.decode(bs)
      (length, numReadBytes) = lengthResult
      content = bs.slice(numReadBytes, numReadBytes + length)
      _ <- if(content.length != length)
        HeaderError.IncompleteInput(content.length, length).left
      else
        ().right
    } yield (content, numReadBytes + length)
  }
  
}

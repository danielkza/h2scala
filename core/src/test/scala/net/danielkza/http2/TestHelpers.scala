package net.danielkza.http2

import scala.language.implicitConversions

import java.io.{InputStream, OutputStream}
import akka.util.{ByteString, ByteStringBuilder}

import scalaz.\/

trait TestHelpers extends util.Implicits {
  def inputStream(s: ByteString): InputStream =
    s.iterator.asInputStream

  def withOutputStream[T](f: OutputStream => T): ByteString = {
    val bytes = new ByteStringBuilder
    f(bytes.asOutputStream)
    bytes.result()
  }
  
  implicit def stringToByteString(s: String): ByteString = ByteString.fromString(s)

  implicit class DisjunctionWithThrow[A, B](self: \/[A, B]) {
    def getOrThrow(): B = self.getOrElse(throw new NoSuchElementException)
  }
}

package net.danielkza.http2

import scala.language.implicitConversions

import java.io.{InputStream, OutputStream}
import akka.util.{ByteString, ByteStringBuilder}

trait TestHelpers extends util.Implicits {
  def inputStream(s: ByteString): InputStream =
    s.iterator.asInputStream

  def withOutputStream[T](f: OutputStream => T): ByteString = {
    val bytes = new ByteStringBuilder
    f(bytes.asOutputStream)
    bytes.result()
  }
  
  implicit def stringToByteString(s: String): ByteString = ByteString.fromString(s)
}

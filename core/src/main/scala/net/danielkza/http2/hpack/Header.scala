package net.danielkza.http2.hpack

import akka.util.ByteString

sealed trait Header {
  val name: ByteString
  val value: ByteString
}

object Header extends {
  case class Plain(name: ByteString, value: ByteString) extends Header
  case class Secure(name: ByteString, value: ByteString) extends Header
  
  private def encodeString(s: String, charset: Option[String]): ByteString =
    ByteString.fromString(s, charset.getOrElse("US-ASCII"))

  def plain(name: String, value: String, charset: Option[String] = None): Header = {
    Plain(encodeString(name, charset), encodeString(value, charset))
  }

  def secure(name: String, value: String, charset: Option[String] = None): Header = {
    Secure(encodeString(name, charset), encodeString(value, charset))
  }
}

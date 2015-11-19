package net.danielkza.http2.api

import scala.language.implicitConversions
import akka.util.ByteString
import akka.http.scaladsl.{model => akkaModel}

sealed trait Header {
  def name: ByteString
  def value: ByteString
  def secure: Boolean
}

object Header extends {
  object PseudoHeaders {
    final val METHOD    = ByteString(":method")
    final val SCHEME    = ByteString(":scheme")
    final val AUTHORITY = ByteString(":authority")
    final val STATUS    = ByteString(":status")
    final val PATH      = ByteString(":path")
    final val HOST      = ByteString("Host")
  }

  import PseudoHeaders._

  private def encode(s: String): ByteString =
    ByteString.fromString(s, "UTF-8")

  case class RawHeader(name: ByteString, value: ByteString, secure: Boolean = false)
    extends Header

  case class WrappedAkkaHeader(akkaHeader: akkaModel.HttpHeader, secure: Boolean = false) extends Header {
    override val name = encode(akkaHeader.name)
    override val value = encode(akkaHeader.value)
  }

  object WrappedAkkaHeader {
    implicit def unwrapAkkaHeader(wrapped: WrappedAkkaHeader): akkaModel.HttpHeader =
      wrapped.akkaHeader
  }

  case class WrappedAkkaStatusCode(akkaStatusCode: akkaModel.StatusCode) extends Header {
    override val name = STATUS
    override val value = ByteString(Integer.toString(akkaStatusCode.intValue))
    override val secure = false
  }

  case class WrappedAkkaMethod(akkaMethod: akkaModel.HttpMethod) extends Header {
    override val name = METHOD
    override val value = ByteString(akkaMethod.value)
    override val secure = false
  }

  def plain(name: ByteString, value: ByteString) = RawHeader(name, value, secure = false)
  def secure(name: ByteString, value: ByteString) = RawHeader(name, value, secure = true)
}

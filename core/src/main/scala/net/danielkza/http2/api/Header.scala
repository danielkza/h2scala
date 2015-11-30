package net.danielkza.http2.api

import akka.http.scaladsl.model.HttpHeader

import scala.language.implicitConversions
import akka.util.ByteString
import akka.http.scaladsl.{model => akkaModel}

sealed trait Header {
  def name: ByteString
  def value: ByteString
  def secure: Boolean
}

object Header extends {
  object Constants {
    final val METHOD         = ByteString(":method")
    final val SCHEME         = ByteString(":scheme")
    final val AUTHORITY      = ByteString(":authority")
    final val STATUS         = ByteString(":status")
    final val PATH           = ByteString(":path")
    final val HOST           = ByteString("Host")
  }

  import Constants._

  private def encode(s: String): ByteString =
    ByteString.fromString(s, "UTF-8")

  case class RawHeader(name: ByteString, value: ByteString, secure: Boolean = false)
    extends Header

  case class WrappedAkkaHeader(akkaHeader: akkaModel.HttpHeader, secure: Boolean = false) extends Header {
    override val name = encode(akkaHeader.name.toLowerCase)
    override val value = encode(akkaHeader.value.toLowerCase)
  }

  implicit def headerFromAkka(header: HttpHeader): WrappedAkkaHeader =
    WrappedAkkaHeader(header, false)

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

  def plain(name: ByteString, value: ByteString): RawHeader =
    RawHeader(name, value, secure = false)

  def plain(name: String, value: String): RawHeader =
    RawHeader(ByteString(name.toLowerCase), ByteString(value.toLowerCase), secure = false)

  def secure(name: ByteString, value: ByteString): RawHeader =
    RawHeader(name, value, secure = true)

  def secure(name: String, value: String): RawHeader =
    RawHeader(ByteString(name.toLowerCase), ByteString(value.toLowerCase), secure = true)
}

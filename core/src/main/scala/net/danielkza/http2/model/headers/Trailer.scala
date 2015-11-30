package net.danielkza.http2.model.headers

import akka.http.scaladsl.model.headers.CustomHeader

import scala.collection.immutable

final case class Trailer(fields: immutable.Seq[String]) extends CustomHeader {
  override def name(): String = "Trailer"

  override def value(): String = fields.mkString(", ")
}

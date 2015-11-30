package net.danielkza.http2.model

import scala.language.implicitConversions
import akka.stream.scaladsl.Source
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}

sealed trait Http2Response {
  def response: HttpResponse
}

object Http2Response {
  case class Simple(response: HttpResponse) extends Http2Response
  case class Promised(response: HttpResponse, promises: Source[(HttpRequest, HttpResponse), Any]) extends Http2Response
  object Promised {
    def apply(response: HttpResponse, promises: Iterable[(HttpRequest, HttpResponse)]): Promised =
      Promised(response, Source(() => promises.iterator))
  }


  case object NoResponse extends Http2Response {
    override val response = HttpResponse(StatusCodes.InternalServerError)
  }
}

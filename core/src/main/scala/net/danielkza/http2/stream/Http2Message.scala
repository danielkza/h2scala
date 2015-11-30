package net.danielkza.http2.stream

import akka.util.ByteString
import akka.http.scaladsl.model.{HttpRequest, HttpEntity}
import net.danielkza.http2.api.Header

private[http2] case class Http2Message(dataStream: Int, responseStream: Int, body: HttpEntity,
                                       headers: Seq[Header], promise: Option[Http2Message]= None,
                                       trailers: Seq[String] = Seq.empty)

private[http2] object Http2Message {
  sealed trait Headers
  object Headers {
    case class Unencoded(headers: Seq[Header]) extends Headers
    case class Encoded(fragment: ByteString) extends Headers
  }
}

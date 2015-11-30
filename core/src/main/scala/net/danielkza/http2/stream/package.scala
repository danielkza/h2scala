package net.danielkza.http2

import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import net.danielkza.http2.protocol.Frame

package object stream {
  private[stream] def headAndTailFlow[T]: Flow[Source[T, Any], (T, Source[T, Unit]), Unit] =
    Flow[Source[T, Any]]
      .flatMapConcat {
        _.prefixAndTail(1)
          .filter(_._1.nonEmpty)
          .map { case (prefix, tail) ⇒ (prefix.head, tail) }
      }
}

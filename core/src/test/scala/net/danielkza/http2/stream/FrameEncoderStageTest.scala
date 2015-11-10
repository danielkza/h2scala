package net.danielkza.http2.stream

import scala.collection.immutable
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import akka.util.ByteString
import net.danielkza.http2.{AkkaStreamsTest, TestHelpers}
import net.danielkza.http2.protocol.{Frame, HTTP2Error}
import net.danielkza.http2.protocol.coders.FrameCoder
import net.danielkza.http2.hpack.Header
import net.danielkza.http2.hpack.coders.HeaderBlockCoder

class FrameEncoderStageTest extends AkkaStreamsTest with TestHelpers {
  import Frame._
  val headerCoder = new HeaderBlockCoder
  val frameCoder = new FrameCoder

  val headers = immutable.Seq(
    ":method" -> "GET",
    ":path"   -> "/",
    "host"    -> "example.com"
  ).map(t => Header.plain(t._1, t._2))

  val okFrames = immutable.Seq(
    Headers(1, None, headerCoder.encode(headers).getOrThrow(), endHeaders=true),
    Data(1, "Line 1\n"),
    Data(1, "Line 2\n", padding=Some("Padding"), endStream=true),
    GoAway(1)
  )

  val errorFrames = immutable.Seq(
    GoAway(1, new HTTP2Error.CompressionError)
  )

  "FrameEncoderStage" should {
    val flow = Flow[Frame].transform(() => new FrameEncoderStage)
    val (pub, sub) = TestSource.probe[Frame]
      .via(flow)
      .toMat(TestSink.probe[ByteString])(Keep.both)
      .run()

    "encode frames correctly" in {
      sub.request(okFrames.length)
      okFrames.foreach(pub.sendNext)
      sub.expectNextN(okFrames.map(frameCoder.encode(_).getOrThrow()))

      sub.request(errorFrames.length)
      errorFrames.foreach(pub.sendNext)
      sub.expectNextN(errorFrames.map(frameCoder.encode(_).getOrThrow()))

      ok
    }
  }
}

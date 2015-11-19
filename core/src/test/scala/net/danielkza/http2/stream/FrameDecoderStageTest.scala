package net.danielkza.http2.stream

import net.danielkza.http2.api.Header

import scala.collection.immutable
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import akka.util.ByteString
import net.danielkza.http2.{AkkaStreamsTest, TestHelpers}
import net.danielkza.http2.protocol.{Frame, HTTP2Error}
import net.danielkza.http2.protocol.coders.FrameCoder
import net.danielkza.http2.hpack.coders.HeaderBlockCoder

class FrameDecoderStageTest extends AkkaStreamsTest with TestHelpers {
  import Frame._
  val headerCoder = new HeaderBlockCoder
  val frameCoder = new FrameCoder

  val headers = immutable.Seq(
    ":method" -> "GET",
    ":path"   -> "/",
    "host"    -> "example.com"
  ).map(t => Header.plain(t._1, t._2))
  val headerBlock = headerCoder.encode(headers).getOrThrow()

  val okFrames = immutable.Seq(
    Headers(1, None, headerBlock, endHeaders=true),
    Data(1, "Line 1\n"),
    Data(1, "Line 2\n\n", padding=Some("Padding"), endStream=true),
    GoAway(1)
  )

  val framesBytes = okFrames.map(frameCoder.encode(_).getOrThrow())

  "FrameDecoderStage" should {
    val flow = Flow[ByteString].transform(() => new FrameDecoderStage)
    val (pub, sub) = TestSource.probe[ByteString]
      .via(flow)
      .toMat(TestSink.probe[Frame])(Keep.both)
      .run()

    "decode frames correctly" in {
      okFrames.zip(framesBytes).foreach { case (frame, bytes) =>
        sub.request(1)
        pub.sendNext(bytes)
        sub.expectNextOrError() must_== Right(frame)
      }

      ok
    }
  }
}

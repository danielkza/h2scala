package net.danielkza.http2.stream

import scala.collection.immutable.Seq
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import akka.util.ByteString
import net.danielkza.http2.{AkkaStreamsTest, TestHelpers}
import net.danielkza.http2.protocol.Frame

class HeaderSplitStageTest extends AkkaStreamsTest with TestHelpers {
  import Frame._

  sequential

  val testMaxSize = 16384
  def zeroes(n: Int) = ByteString.fromArray(Array.fill(n)(0: Byte))

  val streamLen = 4
  val padLen = 1

  "HeaderSplitStage" should {
    val flow = Flow[Frame].transform(() => new HeaderSplitStage(testMaxSize))
    val (pub, sub) = TestSource.probe[Frame]
      .via(flow)
      .toMat(TestSink.probe[Frame])(Keep.both)
      .run()

    def runCase(testCase: (Frame, Seq[Frame])) = {
      val (original, split) = testCase
      sub.request(split.length)
      pub.sendNext(original)

      sub.expectNextN(split.length) must containTheSameElementsAs(split)
    }

    "split frames correctly" in {
      "small Headers frame should not be split" >> runCase(
        Headers(1, None, zeroes(1000), endHeaders = true) -> Seq(
          Headers(1, None, zeroes(1000), endHeaders = true)
        )
      )

      "small PushPromise frame should not be split" >> runCase(
        PushPromise(1, 2, zeroes(1000), endHeaders = true) -> Seq(
          PushPromise(1, 2, zeroes(1000), endHeaders = true)
        )
      )

      "large Headers frame should be split once" >> runCase {
        val firstLen = testMaxSize - Frame.HEADER_LENGTH
        Headers(1, None, zeroes(20000), endHeaders = true) -> Seq(
          Headers(1, None, zeroes(firstLen)),
          Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
        )
      }

      "large Headers frame with padding should be split once" >> runCase {
        val firstLen = testMaxSize - Frame.HEADER_LENGTH - padLen - 20
        Headers(1, None, zeroes(20000), endHeaders = true, padding = Some(zeroes(20))) -> Seq(
          Headers(1, None, zeroes(firstLen), padding = Some(zeroes(20))),
          Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
        )
      }

      "large PushPromise frame should be split once" >> runCase {
        val firstLen = testMaxSize - Frame.HEADER_LENGTH - streamLen
        PushPromise(1, 2, zeroes(20000), endHeaders = true) -> Seq(
          PushPromise(1, 2, zeroes(firstLen)),
          Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
        )
      }

      "large PushPromise frame with padding should be split once" >> runCase {
        val firstLen = testMaxSize - Frame.HEADER_LENGTH - streamLen - padLen - 100

        PushPromise(1, 2, zeroes(20000), endHeaders = true, padding = Some(zeroes(100))) -> Seq(
          PushPromise(1, 2, zeroes(firstLen), padding = Some(zeroes(100))),
          Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
        )
      }

      "very large Headers frame should be split twice" >> runCase {
        val fragLen = testMaxSize - Frame.HEADER_LENGTH

        Headers(1, None, zeroes(40000), endHeaders = true) -> Seq(
          Headers(1, None, zeroes(fragLen)),
          Continuation(1, zeroes(fragLen)),
          Continuation(1, zeroes(40000 - 2 * fragLen), endHeaders = true)
        )
      }
    }
  }
}

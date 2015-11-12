package net.danielkza.http2.stream

import scala.collection.immutable.Seq
import akka.util.ByteString
import org.specs2.mutable.SpecificationLike
import org.specs2.matcher.MatchResult
import net.danielkza.http2.{AkkaStreamsTest, TestHelpers}
import net.danielkza.http2.protocol.Frame

abstract class HeaderStageTest extends AkkaStreamsTest with SpecificationLike with TestHelpers {
  sequential

  val testMaxSize: Int

  def runCase(testCase: (Frame, Seq[Frame])): MatchResult[Any]

  def zeroes(n: Int) = ByteString.fromArray(Array.fill(n)(0: Byte))

  def testHeaders = {
    import Frame._

    val streamLen = 4
    val padLen = 1

    "small Headers frame" >> runCase(
      Headers(1, None, zeroes(1000), endHeaders = true) -> Seq(
        Headers(1, None, zeroes(1000), endHeaders = true)
      )
    )

    "small PushPromise frame" >> runCase(
      PushPromise(1, 2, zeroes(1000), endHeaders = true) -> Seq(
        PushPromise(1, 2, zeroes(1000), endHeaders = true)
      )
    )

    "large Headers frame" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH
      Headers(1, None, zeroes(20000), endHeaders = true) -> Seq(
        Headers(1, None, zeroes(firstLen)),
        Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
      )
    }

    "large Headers frame with padding" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH - padLen - 20
      Headers(1, None, zeroes(20000), endHeaders = true, padding = Some(zeroes(20))) -> Seq(
        Headers(1, None, zeroes(firstLen), padding = Some(zeroes(20))),
        Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
      )
    }

    "large PushPromise frame" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH - streamLen
      PushPromise(1, 2, zeroes(20000), endHeaders = true) -> Seq(
        PushPromise(1, 2, zeroes(firstLen)),
        Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
      )
    }

    "large PushPromise frame" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH - streamLen - padLen - 100

      PushPromise(1, 2, zeroes(20000), endHeaders = true, padding = Some(zeroes(100))) -> Seq(
        PushPromise(1, 2, zeroes(firstLen), padding = Some(zeroes(100))),
        Continuation(1, zeroes(20000 - firstLen), endHeaders = true)
      )
    }

    "very large Headers frame" >> runCase {
      val fragLen = testMaxSize - Frame.HEADER_LENGTH

      Headers(1, None, zeroes(40000), endHeaders = true) -> Seq(
        Headers(1, None, zeroes(fragLen)),
        Continuation(1, zeroes(fragLen)),
        Continuation(1, zeroes(40000 - 2 * fragLen), endHeaders = true)
      )
    }
  }
}

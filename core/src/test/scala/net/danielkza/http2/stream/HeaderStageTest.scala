package net.danielkza.http2.stream

import scala.collection.immutable.Seq
import akka.util.ByteString
import org.specs2.mutable.SpecificationLike
import org.specs2.matcher.MatchResult
import net.danielkza.http2.{AkkaStreamsTest, TestHelpers}
import net.danielkza.http2.protocol.{Frame, HTTP2Error}

abstract class HeaderStageTest extends AkkaStreamsTest with SpecificationLike with TestHelpers {
  import Frame._

  sequential

  val testMaxSize: Int

  def runCase(testCase: (Frame, Seq[Frame])): MatchResult[Any]

  def zeroes(n: Int) = ByteString.fromArray(Array.fill(n)(0: Byte))

  def testPassthrough = {
    import HTTP2Error.Codes._

    def run(f: Frame) = runCase(f -> Seq(f))

    "DATA"         >> run(Data(1, ByteString.empty))
    "PRIORITY"     >> run(Priority(1, StreamDependency(false, 1, 1)))
    "RST_STREAM"   >> run(ResetStream(1, PROTOCOL_ERROR))
    "PING"         >> run(Ping(ByteString.empty))
    "SETTINGS"     >> run(Settings(List()))
    "GOAWAY"       >> run(GoAway(1))
    "Non-standard" >> run(NonStandard(1, 0xFF.toByte, 0xFF.toByte,   ByteString.empty))
  }

  def testHeaders = {

    val streamLen = 4
    val padLen = 1

    "small Headers frame" >> runCase(
      Headers(1, None, zeroes(1000)) -> Seq(
        Headers(1, None, zeroes(1000))
      )
    )

    "small PushPromise frame" >> runCase(
      PushPromise(1, 2, zeroes(1000)) -> Seq(
        PushPromise(1, 2, zeroes(1000))
      )
    )

    "large Headers frame" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH
      Headers(1, None, zeroes(20000)) -> Seq(
        Headers(1, None, zeroes(firstLen), endHeaders = false),
        Continuation(1, zeroes(20000 - firstLen))
      )
    }

    "large Headers frame with padding" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH - padLen - 20
      Headers(1, None, zeroes(20000), padding = Some(zeroes(20))) -> Seq(
        Headers(1, None, zeroes(firstLen), endHeaders = false, padding = Some(zeroes(20))),
        Continuation(1, zeroes(20000 - firstLen))
      )
    }

    "large PushPromise frame" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH - streamLen
      PushPromise(1, 2, zeroes(20000)) -> Seq(
        PushPromise(1, 2, zeroes(firstLen), endHeaders = false),
        Continuation(1, zeroes(20000 - firstLen))
      )
    }

    "large PushPromise frame" >> runCase {
      val firstLen = testMaxSize - Frame.HEADER_LENGTH - streamLen - padLen - 100
      PushPromise(1, 2, zeroes(20000), padding = Some(zeroes(100))) -> Seq(
        PushPromise(1, 2, zeroes(firstLen), endHeaders = false, padding = Some(zeroes(100))),
        Continuation(1, zeroes(20000 - firstLen))
      )
    }

    "very large Headers frame" >> runCase {
      val fragLen = testMaxSize - Frame.HEADER_LENGTH
      Headers(1, None, zeroes(40000)) -> Seq(
        Headers(1, None, zeroes(fragLen), endHeaders = false),
        Continuation(1, zeroes(fragLen), endHeaders = false),
        Continuation(1, zeroes(40000 - 2 * fragLen))
      )
    }
  }
}

package net.danielkza.http2.stream

import akka.util.ByteString
import net.danielkza.http2.protocol.HTTP2Error.ContinuationError

import scala.collection.immutable.Seq
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import org.specs2.matcher.MatchResult
import net.danielkza.http2.protocol.{Frame, HTTP2Error}

class HeaderCollapseStageTest extends HeaderStageTest {
  import Frame._

  val testMaxSize = 16384

  val flow = Flow[Frame].transform(() => new HeaderCollapseStage)
  val graph = TestSource.probe[Frame]
    .via(flow)
    .toMat(TestSink.probe[Frame])(Keep.both)

  lazy val (pub, sub) = graph.run()

  override def runCase(testCase: (Frame, Seq[Frame])): MatchResult[Any] = {
    val (combined, split) = testCase

    sub.request(1)
    split.foreach(pub.sendNext)
    sub.expectNextOrError() must_=== Right(combined)
  }

  def runFailCase(split: Frame*): MatchResult[Any] = {
    sub.request(1)
    split.foreach(pub.sendNext)
    sub.expectNextOrError() must_=== Left(ContinuationError().toException)
  }

  "HeaderCollapseStage" should {
    "collapse" in testHeaders

    "passthrough" in testPassthrough

    "report an error for " in {
      isolated

      "non-Continuation frame" >> runFailCase(
        Headers(1, None, zeroes(100), endHeaders = false),
        Headers(1, None, zeroes(100))
      )

      "unfinished Continuation" >> runFailCase(
        Headers(1, None, zeroes(100), endHeaders = false),
        Continuation(1, zeroes(100), endHeaders = false),
        Headers(1, None, zeroes(100))
      )

      "Continuation with invalid stream" >> runFailCase(
        Headers(1, None, zeroes(100), endHeaders = false),
        Continuation(2, zeroes(100), endHeaders = false),
        Continuation(1, ByteString.empty)
      )

      "unsolicited Continuation" >> runFailCase(
        Continuation(1, zeroes(100))
      )
    }
  }
}


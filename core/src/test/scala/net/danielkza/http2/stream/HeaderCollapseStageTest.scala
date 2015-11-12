package net.danielkza.http2.stream

import scala.collection.immutable.Seq
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import org.specs2.matcher.MatchResult
import net.danielkza.http2.protocol.Frame

class HeaderCollapseStageTest extends HeaderStageTest {
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

  "HeaderCollapseStage" should {
    "collapse" in testHeaders
  }
}


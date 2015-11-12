package net.danielkza.http2.stream

import scala.collection.immutable.Seq
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl._
import org.specs2.matcher.MatchResult
import net.danielkza.http2.protocol.Frame

class HeaderSplitStageTest extends HeaderStageTest {
  val testMaxSize = 16384

  val flow = Flow[Frame].transform(() => new HeaderSplitStage(testMaxSize))
  val graph = TestSource.probe[Frame]
    .via(flow)
    .toMat(TestSink.probe[Frame])(Keep.both)

  lazy val (pub, sub) = graph.run()

  override def runCase(testCase: (Frame, Seq[Frame])): MatchResult[Any] = {
    val (original, split) = testCase
    sub.request(split.length)
    pub.sendNext(original)

    sub.expectNextN(split.length) must containTheSameElementsAs(split)
  }

  "HeaderSplitStage" should {
    "split" in testHeaders
    "passthrough" in testPassthrough
  }
}


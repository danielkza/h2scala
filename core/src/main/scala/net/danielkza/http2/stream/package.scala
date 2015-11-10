package net.danielkza.http2

import akka.stream._
import akka.stream.scaladsl._
import akka.util.ByteString
import net.danielkza.http2.protocol.Frame

package object stream {
  val framing = BidiFlow.fromGraph(FlowGraph.create() { b =>
    val outbound = b.add(Flow[Frame].transform(() => new FrameEncoderStage))
    val inbound = b.add(Flow[ByteString].transform(() => new FrameDecoderStage))
    BidiShape.fromFlows(outbound, inbound)
  })

}

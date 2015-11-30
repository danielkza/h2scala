package net.danielkza.http2.util.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.Stage

object Concentrator {
  def fromFlow[I, O, M](n: Int, flow: Flow[I, O, M], bufferSize: Int = 1,
                        overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure)
    : Graph[ConcentratorShape[I, O], M] =
  {
    FlowGraph.create(flow) { implicit b => flow =>
      import FlowGraph.Implicits._

      val inputs = Vector.tabulate(n) { i =>
        b.add(Flow[I].map { in => (in, i) })
      }

      val outputs = Vector.tabulate(n) { i =>
        b.add(Flow[(O, Int)].collect { case (out, `i`) => out }.buffer(bufferSize, overflowStrategy))
      }

      val inMerge = b.add(Merge[(I, Int)](n))
      val outBroadcast = b.add(Broadcast[(O, Int)](n))

      val indexBypassIn = b.add(Unzip[I, Int])
      val indexBypassOut = b.add(Zip[O, Int])

      for(i <- 0 until n) {
        inputs(i).outlet ~> inMerge.in(i)
      }

      inMerge.out ~> indexBypassIn.in
                     indexBypassIn.out0 ~> flow ~> indexBypassOut.in0
                     indexBypassIn.out1 ~>         indexBypassOut.in1
                                                   indexBypassOut.out ~> outBroadcast

      for(i <- 0 until n) {
        outBroadcast.out(i) ~> outputs(i).inlet
      }

      new ConcentratorShape(inputs.map(_.inlet), outputs.map(_.outlet))
    }
  }

  def fromStage[I, O](n: Int, stage: () => Stage[I, O]): Graph[ConcentratorShape[I, O], Unit] =
    fromFlow(n, Flow[I].transform(stage))
}

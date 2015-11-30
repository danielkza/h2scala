package net.danielkza.http2.stream

import scala.collection.immutable
import akka.stream._
import akka.stream.stage._
import akka.http.scaladsl.model.HttpEntity.{LastChunk, ChunkStreamPart, Chunk}
import net.danielkza.http2.protocol.{HTTP2Error, Frame}
import net.danielkza.http2.protocol.Frame.{Data, Headers}
import net.danielkza.http2.protocol.HTTP2Error.UnacceptableFrameError


class ChunkedDataDecodeStage(val trailers: Boolean = false)
  extends GraphStage[FanOutShape2[Frame, ChunkStreamPart, Headers]]
{
  val in: Inlet[Frame] = Inlet[Frame]("ChunkedDataDecodeStage.in")
  val out0: Outlet[ChunkStreamPart] = Outlet[ChunkStreamPart]("ChunkedDataDecodeStage.out0")
  val out1: Outlet[Headers] = Outlet[Headers]("ChunkedDataDecodeStage.out1")
  override val shape = new FanOutShape2(in, out0, out1)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) { logic =>
    private var completed = false

    setHandler(in, new InHandler {
      override def onPush(): Unit = {
        grab(in) match {
          case d: Data if trailers && d.endStream =>
            failStage(UnacceptableFrameError())
          case d: Data if !trailers && d.endStream =>
            if(d.data.isEmpty) emit(out0, LastChunk, () => completeStage())
            else               emitMultiple(out0, immutable.Seq(Chunk(d.data), LastChunk), () => completeStage())
            completed = true
          case d: Data =>
            emit(out0, Chunk(d.data))
          case h: Headers if trailers && h.endStream =>
            complete(out0)
            emit(out1, h, () => completeStage())
            completed = true
          case h: Headers =>
            failStage(UnacceptableFrameError())
        }
      }

      override def onUpstreamFinish(): Unit = {
        if(!completed)
          failStage(HTTP2Error.HeaderError())
        else
          super.onUpstreamFinish()
      }
    })

    setHandler(out0, new OutHandler {
      override def onPull(): Unit = pull(in)
    })

    setHandler(out1, new OutHandler {
      override def onPull(): Unit = {
        // Do nothing by default. Only forward the demand when emitting the single header frame, after we have already
        // grabbed all the Data frames (the stage set up by `emit` will take care of it)
      }
    })
  }
}

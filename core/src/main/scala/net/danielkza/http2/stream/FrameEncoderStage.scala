package net.danielkza.http2.stream

import akka.stream.stage._
import akka.util.ByteString

import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.coders.FrameCoder

class FrameEncoderStage extends PushStage[Frame, ByteString] {
  private val coder = new FrameCoder

  override def onPush(frame: Frame, ctx: Context[ByteString]): SyncDirective = {
    coder.encodeS(frame).exec(ByteString.newBuilder).leftMap { error =>
      ctx.fail(error.toException())
    }.map { data =>
      ctx.push(data.result())
    }.merge
  }
}

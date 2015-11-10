package net.danielkza.http2.stream

import akka.stream.stage._
import akka.util.ByteString

import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.coders.FrameCoder

class FrameDecoderStage extends PushPullStage[ByteString, Frame] {
  private val coder = new FrameCoder
  private var nextFrameCoder: Option[coder.DecodeState] = None
  private var stash = ByteString.empty
  private var needed = -1

  override def onPush(bytes: ByteString, ctx: Context[Frame]) = {
    stash ++= bytes
    run(ctx)
  }

  override def onPull(ctx: Context[Frame]) = run(ctx)

  override def onUpstreamFinish(ctx: Context[Frame]) =
    if (stash.isEmpty) ctx.finish()
    else ctx.absorbTermination()

  private def run(ctx: Context[Frame]): SyncDirective = {
    if (needed == -1) {
      if (stash.length < Frame.HEADER_LENGTH) {
        pullOrFinish(ctx)
      } else {
        coder.decodeHeader.run(stash).leftMap { error =>
          ctx.fail(error.toException())
        }.map { case (remaining, (nextFrameCoder, remainingFrameLen)) =>
          stash = remaining
          needed = remainingFrameLen
          this.nextFrameCoder = Some(nextFrameCoder)
          run(ctx)
        }.merge
      }
    } else if (stash.length < needed) {
      pullOrFinish(ctx)
    } else {
      nextFrameCoder.get.run(stash).leftMap { error =>
        ctx.fail(error.toException())
      }.map { case (remaining, frame) =>
        stash = remaining
        needed = -1
        this.nextFrameCoder = None
        ctx.push(frame)
      }.merge
    }
  }

  private def pullOrFinish(ctx: Context[Frame]): SyncDirective = {
    if (ctx.isFinishing) ctx.finish()
    else ctx.pull()
  }
}

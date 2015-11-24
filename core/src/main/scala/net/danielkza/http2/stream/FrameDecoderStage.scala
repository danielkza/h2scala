package net.danielkza.http2.stream

import akka.stream.stage._
import akka.util.ByteString

import net.danielkza.http2.protocol.{HTTP2Error, Frame}
import net.danielkza.http2.protocol.coders.FrameCoder
import net.danielkza.http2.stream.FrameDecoderStage.NotHTTP2Exception

class FrameDecoderStage(val waitForPreface: Boolean) extends PushPullStage[ByteString, Frame] {
  import FrameDecoderStage.CONNECTION_PREFACE

  private val coder = new FrameCoder
  private var nextFrameCoder: Option[coder.DecodeState] = None
  private var stash = ByteString.empty
  private var needed = if(!waitForPreface) -1 else CONNECTION_PREFACE.length
  private var prefaceNeeded = !waitForPreface

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
    } else if(prefaceNeeded) {
      val (preface, rest) = stash.splitAt(needed)
      if(preface != CONNECTION_PREFACE)
        ctx.fail(NotHTTP2Exception())
      else {
        prefaceNeeded = false
        needed = -1
        stash = rest
        pullOrFinish(ctx)
      }
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

object FrameDecoderStage {
  case class NotHTTP2Exception(message: String = null, cause: Throwable = null)
    extends RuntimeException(message, cause)

  final val CONNECTION_PREFACE = ByteString.fromString("PRI *\n   HTTP/2.0\\r\\n\\r\\nSM\\r\\n\\r\\n", "US-ASCII")
}

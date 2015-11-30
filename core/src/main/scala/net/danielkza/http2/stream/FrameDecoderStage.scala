package net.danielkza.http2.stream

import akka.stream.stage._
import akka.util.ByteString
import net.danielkza.http2.util.Implicits._
import net.danielkza.http2.protocol.Frame.Settings
import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.coders.FrameCoder
import net.danielkza.http2.stream.FrameDecoderStage.NotHTTP2Exception

import scala.annotation.tailrec
import scalaz.{\/-, -\/}

class FrameDecoderStage(val waitForClientPreface: Boolean) extends PushPullStage[ByteString, Frame] {
  import FrameDecoderStage.CONNECTION_PREFACE

  private val coder = new FrameCoder
  private var nextFrameCoder: Option[coder.DecodeState] = None
  private var stash = ByteString.empty
  private var needed = if(!waitForClientPreface) -1 else CONNECTION_PREFACE.length
  private var clientPrefaceNeeded = waitForClientPreface
  private var settingsFrameNeeded = !waitForClientPreface

  override def onPush(bytes: ByteString, ctx: Context[Frame]) = {
    stash ++= bytes
    run(ctx)
  }

  override def onPull(ctx: Context[Frame]) = run(ctx)

  override def onUpstreamFinish(ctx: Context[Frame]) =
    if (stash.isEmpty) ctx.finish()
    else ctx.absorbTermination()

  @tailrec
  private def run(ctx: Context[Frame]): SyncDirective = {
    if (needed == -1) {
      if (stash.length < Frame.HEADER_LENGTH) {
        pullOrFinish(ctx)
      } else {
        coder.decodeHeader.run(stash) match {
          case -\/(error) =>
            ctx.fail(error)
          case \/-((remaining, (nextFrameCoder, remainingFrameLen))) =>
            stash = remaining
            needed = remainingFrameLen
            this.nextFrameCoder = Some(nextFrameCoder)
            run(ctx)
        }
      }
    } else if (stash.length < needed) {
      pullOrFinish(ctx)
    } else if(clientPrefaceNeeded) {
      val (preface, rest) = stash.splitAt(needed)
      if(preface != CONNECTION_PREFACE)
        ctx.fail(NotHTTP2Exception())
      else {
        clientPrefaceNeeded = false
        settingsFrameNeeded = true
        needed = -1
        stash = rest
        run(ctx)
      }
    } else {
      nextFrameCoder.get.run(stash) match {
        case -\/(error) =>
          ctx.fail(error)
        case \/-((remaining, frame)) =>
          if(settingsFrameNeeded && !frame.isInstanceOf[Settings])
            ctx.fail(NotHTTP2Exception())
          else {
            settingsFrameNeeded = false
            stash = remaining
            needed = -1
            this.nextFrameCoder = None
            ctx.push(frame)
          }
      }
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

  final val CONNECTION_PREFACE = hex_bs"505249202a20485454502f322e300d0a0d0a534d0d0a0d0a"
}

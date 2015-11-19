package net.danielkza.http2.stream

import akka.stream.stage._
import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.Frame.{Headers, PushPromise, Continuation}
import net.danielkza.http2.protocol.HTTP2Error.ContinuationError

class HeaderCollapseStage extends StatefulStage[Frame, Frame] {
  object Passthrough extends State {
    override def onPush(frame: Frame, ctx: Context[Frame]): SyncDirective = {
      frame match {
        case h: Headers if !h.endHeaders =>
          become(Continue(Left(h)))
          ctx.pull()
        case p: PushPromise if !p.endHeaders =>
          become(Continue(Right(p)))
          ctx.pull()
        case c: Continuation =>
          ctx.fail(ContinuationError().toException)
        case _ =>
          ctx.push(frame)
      }
    }
  }

  case class Continue(initialFrame: Either[Headers, PushPromise]) extends State {
    var headerBlock = initialFrame.left.map(_.headerFragment).right.map(_.headerFragment).merge
    val stream = initialFrame.merge.stream

    def collapse: Frame = {
      initialFrame
        .left.map(_.copy(headerFragment = headerBlock, endHeaders = true))
        .right.map(_.copy(headerFragment = headerBlock, endHeaders = true))
        .merge
    }

    override def onPush(frame: Frame, ctx: Context[Frame]): SyncDirective = {
      frame match {
        case Continuation(`stream`, fragment, endHeaders) =>
          headerBlock ++= fragment
          if(endHeaders) {
            become(Passthrough)
            ctx.push(collapse)
          } else {
            ctx.pull()
          }
        case _ =>
          ctx.fail(ContinuationError().toException)
      }
    }
  }

  override def initial = Passthrough
}

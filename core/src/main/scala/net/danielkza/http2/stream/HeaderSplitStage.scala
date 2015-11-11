package net.danielkza.http2.stream

import akka.stream.stage._
import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.Frame.{Headers, PushPromise, Continuation}

class HeaderSplitStage(var maxFrameSize: Int = Frame.DEFAULT_MAX_FRAME_SIZE) extends StatefulStage[Frame, Frame] {
  case object Splitting extends State {
    override def onPush(frame: Frame, ctx: Context[Frame]): SyncDirective = {
      val frames = frame match {
        case h: Headers if h.endHeaders =>
          splitFrame(Left(h))
        case p: PushPromise if p.endHeaders =>
          splitFrame(Right(p))
        case _ =>
          return ctx.push(frame)
      }

      emit(frames.iterator, ctx)
    }
  }

  def initial = Splitting

  def maxContinuationFragmentSize = maxFrameSize - Frame.HEADER_LENGTH

  def splitFrame(frame: Either[Headers, PushPromise]): Seq[Frame] = {
    var size = Frame.HEADER_LENGTH
    val block = frame.left.map { h =>
      h.padding.foreach { size += _.length + 1 }
      h.streamDependency.foreach { _ => size += 5 }

      h.headerFragment
    }.right.map { p =>
      p.padding.foreach { size += _.length + 1 }
      size += 4

      p.headerFragment
    }.merge

    size += block.length
    if(size <= maxFrameSize)
      return Seq(frame.merge)

    val headFragSize = block.length - (size - maxFrameSize)
    val split = block.splitAt(headFragSize)

    val headFrag = split._1
    val headFrame = frame.left.map { h =>
      h.copy(headerFragment = headFrag, endHeaders = false)
    }.right.map { p =>
      p.copy(headerFragment = headFrag, endHeaders = false)
    }.merge

    var rest = split._2
    val fragSize = maxContinuationFragmentSize

    val frames = Seq.newBuilder[Frame]
    frames.sizeHint(1 + (rest.length / fragSize))
    frames += headFrame

    while(rest.length > fragSize) {
      val split = rest.splitAt(fragSize)
      frames += Continuation(headFrame.stream, split._1, endHeaders = false)
      rest = split._2
    }

    frames += Continuation(headFrame.stream, rest, endHeaders = true)
    frames.result()
  }
}

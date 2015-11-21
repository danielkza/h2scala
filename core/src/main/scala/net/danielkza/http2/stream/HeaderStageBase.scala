package net.danielkza.http2.stream

import akka.stream.stage.{LifecycleContext, PushStage}
import net.danielkza.http2.hpack.coders.HeaderBlockCoder

abstract class HeaderStageBase[In, Out](val initialMaxTableSize: Int) extends PushStage[In, Out] {
  private var _headerBlockCoder: HeaderBlockCoder = null

  override def postStop(): Unit = {
    _headerBlockCoder = null
    super.postStop()
  }

  override def preStart(ctx: LifecycleContext): Unit = {
    super.preStart(ctx)
    _headerBlockCoder = new HeaderBlockCoder(initialMaxTableSize)
  }

  def headerBlockCoder: HeaderBlockCoder = {
    if(_headerBlockCoder == null)
      throw new IllegalStateException("Header block coder uninitialized")

    _headerBlockCoder
  }

  def updateHeaderBlockCoder(f: HeaderBlockCoder => HeaderBlockCoder): Unit = {
    val newCoder = f(headerBlockCoder)
    if(newCoder == null)
      throw new IllegalArgumentException("Header block coder cannot be made null")

    _headerBlockCoder = newCoder
  }
}

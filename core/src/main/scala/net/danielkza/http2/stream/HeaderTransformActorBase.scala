package net.danielkza.http2.stream

import akka.actor.Actor
import akka.util.ByteString
import net.danielkza.http2.api.Header
import net.danielkza.http2.hpack.coders.HeaderBlockCoder
import net.danielkza.http2.protocol.HTTP2Error

abstract class HeaderTransformActorBase(val initialMaxTableSize: Int) extends Actor {
  private var _headerBlockCoder: HeaderBlockCoder = null

  override def postStop(): Unit = {
    _headerBlockCoder = null
    super.postStop()
  }

  override def preStart(): Unit = {
    super.preStart()
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

object HeaderTransformActorBase {
  sealed trait Message
  case class Fragment(block: ByteString) extends Message
  case class Headers(headers: Seq[Header]) extends Message
  case class SetTableMaxSize(size: Int) extends Message
  case class Failure(error: HTTP2Error) extends Message
  case object OK extends Message
}

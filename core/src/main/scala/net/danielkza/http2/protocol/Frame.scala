package net.danielkza.http2.protocol

import akka.util.ByteString

sealed trait Frame {
  def tpe: Byte
  def flags: Byte 
}

sealed abstract class KnownFrame(frameType: Frame.Type) extends Frame {
  override val tpe: Byte = frameType.id.toByte
  def flags: Byte = 0
}

object Frame {
  case class StreamDependency(exclusive: Boolean, stream: Int, weight: Byte)
  
  case class Data(data: ByteString, endStream: Boolean)
    extends KnownFrame(Type.DATA)
  {
    override def flags: Byte = if(endStream) Flags.DATA.END_STREAM else 0
  }
  
  case class Headers(streamDependency: Option[StreamDependency], headerFragment: ByteString, endStream: Boolean,
                     endHeaders: Boolean)
    extends KnownFrame(Type.HEADERS)
  {
    override def flags: Byte = {
      var flags: Byte = if(streamDependency.isDefined) Flags.HEADERS.PRIORITY else 0
      if(endStream) flags = (flags | Flags.HEADERS.END_STREAM).toByte
      if(endHeaders) flags = (flags | Flags.HEADERS.END_HEADERS).toByte
      flags
    }
  }
  
  case class Priority(streamDependency: StreamDependency)
    extends KnownFrame(Type.PRIORITY)
  
  case class ResetStream(errorCode: Int)
    extends KnownFrame(Type.RST_STREAM)
  
  case class PushPromise(stream: Int, headerFragment: ByteString, endHeaders: Boolean)
    extends KnownFrame(Type.PUSH_PROMISE)
  {
    override def flags: Byte = if(endHeaders) Flags.PUSH_PROMISE.END_HEADERS else 0
  }
  
  case class Ping(data: Long, ack: Boolean)
    extends KnownFrame(Type.PING)
  {
    override def flags: Byte = if(ack) Flags.PING.ACK else 0
  }
  
  case class Settings(settings: List[(Short, Int)], ack: Boolean)
    extends KnownFrame(Type.SETTINGS)
  {
    override def flags: Byte = if(ack) Flags.SETTINGS.ACK else 0
  }
  
  case class GoAway(lastStream: Int, errorCode: Int, debugData: ByteString)
    extends KnownFrame(Type.GOAWAY)
  
  case class WindowUpdate(windowIncrement: Int)
    extends KnownFrame(Type.WINDOW_UPDATE)
  
  case class Continuation(headerFragment: ByteString, endHeaders: Boolean)
    extends KnownFrame(Type.CONTINUATION)
  
  case class Unknown(override val tpe: Byte, override val flags: Byte, payload: ByteString)
    extends Frame
  
  object Type extends Enumeration {
    val DATA          = Value(0x0)
    val HEADERS       = Value(0x1)
    val PRIORITY      = Value(0x2)
    val RST_STREAM    = Value(0x3)
    val SETTINGS      = Value(0x4)
    val PUSH_PROMISE  = Value(0x5)
    val PING          = Value(0x6)
    val GOAWAY        = Value(0x7)
    val WINDOW_UPDATE = Value(0x8)
    val CONTINUATION  = Value(0x9)
    
    val UNKNOWN       = Value(-1)
  }
  type Type = Type.Value
  
  object Flags {
    object DATA {
      final val END_STREAM: Byte = 0x1
      final val PADDED    : Byte = 0x8
    }
    
    object HEADERS {
      final val END_STREAM: Byte  = 0x1
      final val END_HEADERS: Byte = 0x4
      final val PADDED: Byte      = 0x8
      final val PRIORITY: Byte    = 0x20
    }
    
    object PUSH_PROMISE {
      final val END_HEADERS: Byte  = 0x4
      final val PADDED: Byte       = 0x8
    }
    
    object PING {
      final val ACK: Byte  = 0x1
    }
    
    object SETTINGS {
      final val ACK: Byte  = 0x1
    }
  }
}

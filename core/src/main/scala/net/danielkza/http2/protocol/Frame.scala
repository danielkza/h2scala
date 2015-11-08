package net.danielkza.http2.protocol

import akka.util.ByteString

sealed trait Frame {
  def tpe: Byte
  def flags: Byte
  def withFlags(flags: Byte): Frame
}

sealed abstract class KnownFrame(frameType: Frame.Type) extends Frame {
  override val tpe: Byte = frameType.id.toByte
  def flags: Byte = 0
}

object Frame {
  case class StreamDependency(exclusive: Boolean, stream: Int, weight: Int)
  
  case class Data(
    data: ByteString,
    endStream: Boolean = false,
    padding: Option[ByteString] = None
  ) extends KnownFrame(Type.DATA) {
    override def flags: Byte = {
      var flags = if(endStream) Flags.DATA.END_STREAM else 0
      padding.foreach { _ => flags |= Flags.DATA.PADDED }
      flags.toByte
    }
    
    override def withFlags(flags: Byte): Data =
      copy(endStream = (flags & Flags.DATA.END_STREAM) != 0)
  }
  
  case class Headers(
    streamDependency: Option[StreamDependency],
    headerFragment: ByteString,
    endStream: Boolean = false,
    endHeaders: Boolean = false,
    padding: Option[ByteString] = None
  ) extends KnownFrame(Type.HEADERS) {
    override def flags: Byte = {
      var flags = if(streamDependency.isDefined) Flags.HEADERS.PRIORITY else 0
      padding.foreach { _ => flags |= Flags.HEADERS.PADDED }
      if(endStream) flags |= Flags.HEADERS.END_STREAM
      if(endHeaders) flags |= Flags.HEADERS.END_HEADERS
      
      flags.toByte
    }
    
    override def withFlags(flags: Byte): Headers =
      copy(endStream = (flags & Flags.HEADERS.END_STREAM) != 0,
           endHeaders = (flags & Flags.HEADERS.END_HEADERS) != 0) 
  }
  
  case class Priority(
    streamDependency: StreamDependency
  ) extends KnownFrame(Type.PRIORITY) {
    override def withFlags(flags: Byte): Priority = this
  }
  
  case class ResetStream(
    errorCode: Int
  ) extends KnownFrame(Type.RST_STREAM) {
    override def withFlags(flags: Byte): ResetStream = this
  }
  case class PushPromise(
    stream: Int,
    headerFragment: ByteString,
    endHeaders: Boolean = false,
    padding: Option[ByteString] = None
  ) extends KnownFrame(Type.PUSH_PROMISE) {
    override def flags: Byte = {
      var flags = if(endHeaders) Flags.PUSH_PROMISE.END_HEADERS else 0
      padding.foreach { _ => flags |= Flags.PUSH_PROMISE.PADDED }
      flags.toByte
    }
    
    override def withFlags(flags: Byte): PushPromise = 
      copy(endHeaders = (flags & Flags.PUSH_PROMISE.END_HEADERS) != 0) 
  }
  
  case class Ping(
    data: ByteString,
    ack: Boolean = false
  ) extends KnownFrame(Type.PING) {
    override def flags: Byte =
      if(ack) Flags.PING.ACK else 0

    override def withFlags(flags: Byte): Ping = 
      copy(ack = (flags & Flags.PING.ACK) != 0)
  }
  
  case class Settings(
    settings: List[(Short, Int)],
    ack: Boolean = false
  ) extends KnownFrame(Type.SETTINGS) {
    override def flags: Byte =
      if(ack) Flags.SETTINGS.ACK else 0
    
    override def withFlags(flags: Byte): Settings = 
      copy(ack = (flags & Flags.SETTINGS.ACK) != 0)
  }
  
  case class GoAway(
    lastStream: Int,
    errorCode: Int,
    debugData: ByteString
  ) extends KnownFrame(Type.GOAWAY) {
    override def withFlags(flags: Byte): GoAway = this
  }
  
  case class WindowUpdate(
    windowIncrement: Int
  ) extends KnownFrame(Type.WINDOW_UPDATE) {
    override def withFlags(flags: Byte): WindowUpdate= this
  }
  
  case class Continuation(
    headerFragment: ByteString,
    endHeaders: Boolean = false
  ) extends KnownFrame(Type.CONTINUATION) {
    override def withFlags(flags: Byte): Continuation = this
  }
  
  case class Unknown(
    override val tpe: Byte,
    override val flags: Byte,
    payload: ByteString
  ) extends Frame {
    override def withFlags(flags: Byte): Unknown = copy(flags = flags)
  }
  
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

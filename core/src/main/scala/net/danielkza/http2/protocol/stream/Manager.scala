package net.danielkza.http2.protocol.stream

import scala.collection.mutable

trait Manager { self: Role with Direction =>
  protected val streams = new mutable.LongMap[Http2Stream]
  protected var lastId: Int = 0
  val maxStreams: Int

  def validateId(id: Int): Boolean

}

object Manager {
  final class IncomingClient(val maxStreams: Int) extends Manager with Direction.Incoming with Role.Client
  final class IncomingServer(val maxStreams: Int) extends Manager with Direction.Incoming with Role.Server
  final class OutgoingClient(val maxStreams: Int) extends Manager with Direction.Outgoing with Role.Client
  final class OutgoingServer(val maxStreams: Int) extends Manager with Direction.Outgoing with Role.Server
}

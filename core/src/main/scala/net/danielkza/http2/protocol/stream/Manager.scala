package net.danielkza.http2.protocol.stream

import scala.collection.mutable

trait Manager { self: Role with Direction =>
  protected val streams = new mutable.LongMap[Stream]
  protected var lastId: Int = 0

  def validateId(id: Int): Boolean

}

object Manager {
  final class IncomingClient extends Manager with Direction.Incoming with Role.Client
  final class IncomingServer extends Manager with Direction.Incoming with Role.Server
  final class OutgoingClient extends Manager with Direction.Outgoing with Role.Client
  final class OutgoingServer extends Manager with Direction.Outgoing with Role.Server
}

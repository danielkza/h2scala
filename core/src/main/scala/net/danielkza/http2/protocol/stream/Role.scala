package net.danielkza.http2.protocol.stream

sealed trait Role { self: Manager =>

}

object Role {
  trait Client extends Role { self: Manager =>
    override def validateId(id: Int): Boolean = id % 2 != 0
  }

  trait Server extends Role { self: Manager =>
    override def validateId(id: Int): Boolean = id % 2 == 0
  }
}

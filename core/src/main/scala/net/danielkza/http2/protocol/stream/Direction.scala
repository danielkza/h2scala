package net.danielkza.http2.protocol.stream

import net.danielkza.http2.protocol.Frame.StreamDependency
import net.danielkza.http2.protocol.{HTTP2Error, Frame}

import scalaz.\/
import scalaz.syntax.either._

sealed trait Direction { self: Manager =>

}

object Direction {
  trait Incoming extends Direction { self: Manager =>
    import Frame._

    private def closeUnusedStreams(maxId: Int): Unit = {
      val id = maxId
      var done: Boolean = true

      while(maxId > 0) {
        done = true
        streams.getOrElseUpdate(id, {
          done = false
          new Stream(id, state = Stream.Closed)
        })

        if(done)
          return
      }
    }

    private def newStream(id: Int, promised: Boolean, dependency: Option[StreamDependency]): \/[HTTP2Error, Stream] = {
      if(streams.contains(id))
        return HTTP2Error.InvalidStream().left

      closeUnusedStreams(id)
      val (parent, weight) = dependency map(d => (d.stream, d.weight)) getOrElse (0, 16)
      val state = if(promised) Stream.ReservedRemote else Stream.Idle
      val stream = new Stream(id, parentStream = parent, weight = weight, state = state)

      streams(id) = stream
      stream.right
    }

    def receive(frame: Frame): \/[HTTP2Error, Frame] = {
      (frame match {
        case h: Headers if validateId(h.stream) && !streams.contains(h.stream) =>
          newStream(h.stream, false, h.streamDependency)
        case pp: PushPromise if streams.contains(pp.stream) && validateId(pp.promisedStream) &&
                                !streams.contains(pp.promisedStream) =>
          newStream(pp.promisedStream, true, None)
        case f =>
          streams.get(f.stream) map { stream =>
            stream.receive(f)
          } getOrElse HTTP2Error.InvalidStream().left
      }).map(_ => frame)
    }
  }

  trait Outgoing extends Direction { self: Manager =>
    def nextId(previous: Int): Int =
      if(validateId(previous)) previous + 2 else previous + 1

    private def allocate(f: Int => Stream): Option[Stream] = {
      // The stream ID will wrap back to negative if it goes past 2^31, which is also the maximum acceptable value.
      // In this case, there's nothing to do and we just fail the allocation, and the peer should open a new connection
      // if desired (RFC-7540, Section 5.1.1)

      val newId = nextId(lastId)
      if(newId <= 0)
        None
      else {
        val stream = f(newId)
        streams(newId) = stream
        lastId = newId

        Some(stream)
      }
    }

    def open(dependency: Option[StreamDependency]): Option[Stream] = {
      val (parent, weight) = dependency map(d => (d.stream, d.weight)) getOrElse (0, 16)

      allocate { newId =>
        new Stream(newId, Stream.Idle, parentStream = parent, weight = weight)
      }
    }

    def reserve(): Option[Stream] = {
      allocate { newId =>
        new Stream(newId, Stream.ReservedLocal)
      }
    }
  }
}

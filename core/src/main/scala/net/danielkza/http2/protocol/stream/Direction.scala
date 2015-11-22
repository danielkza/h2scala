package net.danielkza.http2.protocol.stream

import scalaz.\/
import scalaz.syntax.either._
import net.danielkza.http2.protocol.{HTTP2Error, Frame}
import net.danielkza.http2.protocol.Frame.StreamDependency

sealed trait Direction { self: Manager => }

object Direction {
  trait Incoming extends Direction { self: Manager =>
    import Frame._

    private var openStreams = 0

    private def closeUnusedStreams(maxId: Int): Unit = {
      val id = maxId
      var done: Boolean = true

      while(maxId > 0) {
        done = true
        streams.getOrElseUpdate(id, {
          done = false
          new Http2Stream(id, state = Http2Stream.Closed)
        })

        if(done)
          return
      }
    }

    private def newStream(id: Int, promised: Boolean, dependency: Option[StreamDependency])
      : \/[HTTP2Error, Http2Stream] =
    {
      if(streams.contains(id))
        return HTTP2Error.InvalidStream().left

      if(!promised && openStreams >= maxStreams)
        return HTTP2Error.ExcessiveStreams().left

      closeUnusedStreams(id)
      val (parent, weight) = dependency map(d => (d.stream, d.weight)) getOrElse (0, 16)
      val state = if(promised) {
        Http2Stream.ReservedRemote
      } else {
        openStreams += 1
        Http2Stream.Idle
      }
      val stream = new Http2Stream(id, parentStream = parent, weight = weight, state = state)

      streams(id) = stream
      stream.right
    }

    def receive(frame: Frame): \/[HTTP2Error, Http2Stream] = {
      frame match {
        case h: Headers if validateId(h.stream) && !streams.contains(h.stream) =>
          newStream(h.stream, false, h.streamDependency)
        case pp: PushPromise if streams.contains(pp.stream) && validateId(pp.promisedStream) &&
                                !streams.contains(pp.promisedStream) =>
          newStream(pp.promisedStream, true, None)
        case f =>
          streams.get(f.stream) map { stream =>
            stream.receive(f)
            stream.right
          } getOrElse HTTP2Error.InvalidStream().left
      }
    }

  }

  trait Outgoing extends Direction { self: Manager =>
    def nextId(previous: Int): Int =
      if(validateId(previous)) previous + 2 else previous + 1

    private def allocate(f: Int => Http2Stream): Option[Http2Stream] = synchronized {
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

    def open(dependency: Option[StreamDependency]): Option[Http2Stream] = {
      val (parent, weight) = dependency map(d => (d.stream, d.weight)) getOrElse (0, 16)

      allocate { newId =>
        new Http2Stream(newId, Http2Stream.Idle, parentStream = parent, weight = weight)
      }
    }

    def reserve(): Option[Http2Stream] = {
      allocate { newId =>
        new Http2Stream(newId, Http2Stream.ReservedLocal)
      }
    }
  }
}

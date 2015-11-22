package net.danielkza.http2.protocol.stream

import net.danielkza.http2.protocol.{Frame, HTTP2Error}

import scalaz.\/
import scalaz.syntax.either._

class Http2Stream(
  val id: Int,
  protected var state: Http2Stream.State,
  protected var parentStream: Int = 0,
  protected var weight: Int = 16,
  protected var flowWindow: Int = 0)
{
  import HTTP2Error._
  import Http2Stream._

  private def applyState(newState: Option[State]) = {
    (state, newState) match {
      case (_, Some(s)) =>
        s.right
      case (Closed | HalfClosedLocal | HalfClosedRemote, _) =>
        StreamClosedError().left
      case _  =>
        UnacceptableFrameError().left
    }
  }

  def receive(frame: Frame): \/[HTTP2Error, State] = {
    applyState(state.onReceive.lift(frame))
  }

  def send(frame: Frame): \/[HTTP2Error, State] = {
    applyState(state.onSend.lift(frame))
  }
}

object Http2Stream {
  import Frame._

  sealed trait State {
    def onReceive: PartialFunction[Frame, State] = defaults
    def onSend: PartialFunction[Frame, State] = defaults

    def defaults: PartialFunction[Frame, State] = {
      case rst: ResetStream => Closed
      case p: Priority      => this
      case w: WindowUpdate  => this
    }

    def withDefaults(f: PartialFunction[Frame, State]): PartialFunction[Frame, State] =
      f orElse defaults
  }

  case object Idle extends State {
    override val onReceive = {
      case h: Headers if h.endStream => HalfClosedRemote
      case _: Headers                => Open
      case _: Priority               => this
    }

    override val onSend = {
      case h: Headers if h.endStream => HalfClosedLocal
      case _: Headers                => Open
      case _: Priority               => this
    }
  }

  case object ReservedLocal extends State {
    override val onSend = withDefaults {
      case _: Headers => HalfClosedRemote
    }
  }

  case object ReservedRemote extends State {
    override val onReceive = withDefaults {
      case _: Headers => HalfClosedLocal
    }
  }

  case object Open extends State {
    override val onReceive = {
      case d: Data if d.endStream    => HalfClosedRemote
      case h: Headers if h.endStream => HalfClosedRemote
      case _: ResetStream            => Closed
      case _                         => this
    }

    override val onSend = {
      case d: Data if d.endStream    => HalfClosedLocal
      case h: Headers if h.endStream => HalfClosedLocal
      case _: ResetStream            => Closed
      case _                         => this
    }
  }

  case object HalfClosedLocal extends State {
    override val onReceive = {
      case d: Data if d.endStream    => Closed
      case h: Headers if h.endStream => Closed
      case _: ResetStream            => Closed
      case _                         => this
    }
  }

  case object HalfClosedRemote extends State {
    override val onSend = {
      case d: Data if d.endStream    => Closed
      case h: Headers if h.endStream => Closed
      case _: ResetStream            => Closed
      case _                         => this
    }
  }

  case object Closed extends State

  case object Dead extends State {
    override val onReceive = PartialFunction.empty
    override val onSend    = PartialFunction.empty
  }

//  sealed trait PriorityTree {
//    def weight: Int
//  }
//
//  object PriorityTree {
//    case class Leaf(stream: Int, weight: Int) extends PriorityTree
//    case class Node(children: PriorityTree, weight: Int) extends PriorityTree
//  }
}

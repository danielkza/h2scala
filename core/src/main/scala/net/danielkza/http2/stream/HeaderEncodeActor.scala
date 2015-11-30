package net.danielkza.http2.stream

import akka.actor.Props
import net.danielkza.http2.protocol.HTTP2Error

import scala.language.implicitConversions
import scalaz.{-\/, \/-}

class HeaderEncodeActor(initialMaxTableSize: Int)
  extends HeaderTransformActorBase(initialMaxTableSize)
{
  import HeaderEncodeActor._
  import HeaderTransformActorBase._

  override def receive: Receive = {
    case Headers(headers) =>
      headerBlockCoder.encode(headers) match {
        case -\/(error) => sender ! compressionError
        case \/-(block) => sender ! Fragment(block)
      }
    case SetTableMaxSize(size) =>
      if(size < 0) {
        sender ! settingsError
      } else {
        updateHeaderBlockCoder(_.withMaxCapacity(size))
        sender ! OK
      }
  }
}

object HeaderEncodeActor {
  def props(initialMaxTableSize: Int): Props = Props(new HeaderEncodeActor(initialMaxTableSize))

  private [HeaderEncodeActor] lazy val compressionError =
    HTTP2Error.CompressionError().withErrorMessage("Header compression failure in encoding").toException

  private [HeaderEncodeActor] lazy val settingsError =
    HTTP2Error.SettingsError().withErrorMessage("Invalid SETTINGS_MAX_HEADER_TABLE_SIZE").toException
}

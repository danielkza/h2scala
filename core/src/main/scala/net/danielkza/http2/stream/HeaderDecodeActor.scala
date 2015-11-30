package net.danielkza.http2.stream

import scala.language.implicitConversions
import scalaz.{-\/, \/-}
import akka.actor._
import net.danielkza.http2.protocol.HTTP2Error

class HeaderDecodeActor(initialMaxTableSize: Int)
  extends HeaderTransformActorBase(initialMaxTableSize)
{
  import HeaderDecodeActor._
  import HeaderTransformActorBase._

  override def receive: Receive = {
    case Fragment(block) =>
      headerBlockCoder.decode(block) match {
        case \/-((headers, readLen)) if readLen == block.length =>
          sender ! Headers(headers)
        case -\/(error) =>
          sender ! Failure(compressionError(error.toString))
      }
    case SetTableMaxSize(size) =>
      if(size < 0) {
        sender ! Failure(settingsError)
      } else {
        updateHeaderBlockCoder(_.withMaxCapacity(size))
        sender ! OK
      }
  }
}

object HeaderDecodeActor {
  def props(initialMaxTableSize: Int): Props = Props(new HeaderDecodeActor(initialMaxTableSize))

  private [HeaderDecodeActor] def compressionError(message: String) =
    HTTP2Error.CompressionError().withErrorMessage(s"Header compression failure in decoding: $message")

  private [HeaderDecodeActor] lazy val settingsError =
    HTTP2Error.SettingsError().withErrorMessage("Invalid SETTINGS_MAX_HEADER_TABLE_SIZE")
}

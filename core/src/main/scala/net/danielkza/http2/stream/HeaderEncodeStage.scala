package net.danielkza.http2.stream

import net.danielkza.http2.protocol.HTTP2Error

import scala.language.implicitConversions
import scalaz.{-\/, \/-}
import akka.util.ByteString
import akka.stream.stage._
import net.danielkza.http2.api.Header

class HeaderEncodeStage(initialMaxTableSize: Int)
  extends HeaderStageBase[HeaderEncodeStage.In, ByteString](initialMaxTableSize)
{
  import HeaderEncodeStage._

  override def onPush(elem: In, ctx: Context[ByteString]): SyncDirective = {
    elem match {
      case Headers(headers) =>
        headerBlockCoder.encode(headers) match {
          case -\/(error) => ctx.fail(compressionError)
          case \/-(block) => ctx.push(block)
        }
      case SetTableMaxSize(size) =>
        if(size < 0) {
          ctx.fail(settingsError)
        } else {
          updateHeaderBlockCoder(_.withMaxCapacity(size))
          ctx.pull()
        }
    }
  }
}

object HeaderEncodeStage {
  sealed trait In
  case class Headers(headers: Seq[Header]) extends In
  case class SetTableMaxSize(size: Int) extends In

  implicit def inFromHeaders(headers: Seq[Header]): Headers =
    Headers(headers)

  private [HeaderEncodeStage] lazy val compressionError =
    HTTP2Error.CompressionError().withErrorMessage("Header compression failure in encoding").toException

  private [HeaderEncodeStage] lazy val settingsError =
    HTTP2Error.SettingsError().withErrorMessage("Invalid SETTINGS_MAX_HEADER_TABLE_SIZE").toException
}

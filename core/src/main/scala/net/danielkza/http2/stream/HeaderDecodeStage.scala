package net.danielkza.http2.stream

import net.danielkza.http2.protocol.HTTP2Error

import scala.language.implicitConversions
import scalaz.\/-
import akka.util.ByteString
import akka.stream.stage._
import net.danielkza.http2.api.Header

class HeaderDecodeStage(initialMaxTableSize: Int)
  extends HeaderStageBase[HeaderDecodeStage.In, Seq[Header]](initialMaxTableSize)
{
  import HeaderDecodeStage._

  override def onPush(elem: In, ctx: Context[Seq[Header]]): SyncDirective = {
    elem match {
      case Block(block) =>
        headerBlockCoder.decode(block) match {
          case \/-((headers, readLen)) if readLen == block.length =>
            ctx.push(headers)
          case _ =>
            ctx.fail(compressionError)
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

object HeaderDecodeStage {
  sealed trait In
  case class Block(block: ByteString) extends In
  case class SetTableMaxSize(size: Int) extends In

  implicit def inFromByteString(block: ByteString): Block =
    Block(block)

  private [HeaderDecodeStage] lazy val compressionError =
    HTTP2Error.CompressionError().withErrorMessage("Header compression failure in decoding").toException

  private [HeaderDecodeStage] lazy val settingsError =
    HTTP2Error.SettingsError().withErrorMessage("Invalid SETTINGS_MAX_HEADER_TABLE_SIZE").toException
}

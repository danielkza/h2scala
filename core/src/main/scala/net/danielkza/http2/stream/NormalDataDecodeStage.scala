package net.danielkza.http2.stream

import akka.util.ByteString
import akka.stream.stage.{SyncDirective, Context, PushStage}
import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.Frame.Data

private class NormalDataDecodeStage(val ignoreNonData: Boolean = false) extends PushStage[Frame, ByteString] {
  override def onPush(frame: Frame, ctx: Context[ByteString]): SyncDirective = {
    frame match {
      case d: Data if d.endStream => ctx.pushAndFinish(d.data)
      case d: Data                => ctx.push(d.data)
    }
  }
}

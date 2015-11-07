import akka.util.ByteString
import net.danielkza.http2.protocol.Frame
import net.danielkza.http2.protocol.coders.FrameCoder
val fc = new FrameCoder
fc.encode(Frame.Data(ByteString("test"), false))

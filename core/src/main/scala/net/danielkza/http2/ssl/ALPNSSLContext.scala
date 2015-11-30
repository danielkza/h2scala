package net.danielkza.http2.ssl

import java.util
import javax.net.ssl.{SSLContext, SSLEngine, SSLException}
import scala.collection.JavaConversions._
import scala.collection.immutable
import org.eclipse.jetty.alpn.ALPN

class ALPNSSLContext(context: SSLContext, orderedProtocols: immutable.Seq[String])
  extends WrappedSSLContext(context)
{
  private class Provider(engine: SSLEngine) extends ALPN.ClientProvider with ALPN.ServerProvider {
    override def protocols(): util.List[String] =
      orderedProtocols

    override def selected(protocol: String): Unit = {
      if(!orderedProtocols.contains(protocol))
        throw new SSLException(s"ALPN: Unsupported protocol $protocol")
    }

    override def select(serverProtocols: util.List[String]): String = {
      serverProtocols.find(orderedProtocols.contains(_)).getOrElse {
        throw new SSLException(s"ALPN: No common supported protocols with server")
      }
    }

    override def unsupported(): Unit =
      throw new SSLException(s"ALPN: Unsupported by peer")
  }

  override def mapEngine(engine: SSLEngine): SSLEngine = {
    ALPN.put(engine, new Provider(engine))
    ALPN.debug = true
    engine
  }
}

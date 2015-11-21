package net.danielkza.http2.ssl

import java.security.SecureRandom
import javax.net.ssl._

class WrappedSSLContext(context: SSLContext)
  extends SSLContext(WrappedSSLContext.SpiWrapper(WrappedSSLContext.getSpi(context), this), context.getProvider,
                     context.getProtocol)
{
  def mapEngine(engine: SSLEngine): SSLEngine = engine

  def mapSocketFactory(socketFactory: SSLSocketFactory) = socketFactory

  def mapServerSocketFactory(serverSocketFactory: SSLServerSocketFactory) = serverSocketFactory

  def mapSessionContext(context: SSLSessionContext): SSLSessionContext = context
}

object WrappedSSLContext {
  private[WrappedSSLContext$] case class SpiWrapper(wrapped: SSLContextSpi, parent: WrappedSSLContext)
    extends SSLContextSpi
  {
    override def engineCreateSSLEngine(): SSLEngine =
      parent.mapEngine(wrapped.engineCreateSSLEngine())

    override def engineGetSocketFactory(): SSLSocketFactory =
      parent.mapSocketFactory(wrapped.engineGetSocketFactory())

    override def engineInit(keyManagers: Array[KeyManager], trustManagers: Array[TrustManager],
                            secureRandom: SecureRandom): Unit =
    {
      wrapped.engineInit(keyManagers, trustManagers, secureRandom)
    }

    override def engineCreateSSLEngine(peerHost: String, peerPort: Int): SSLEngine =
      parent.mapEngine(super.engineCreateSSLEngine(peerHost, peerPort))

    override def engineGetClientSessionContext(): SSLSessionContext =
      parent.mapSessionContext(super.engineGetClientSessionContext())

    override def engineGetServerSessionContext(): SSLSessionContext =
      parent.mapSessionContext(super.engineGetServerSessionContext())

    override def engineGetServerSocketFactory(): SSLServerSocketFactory =
      parent.mapServerSocketFactory(super.engineGetServerSocketFactory())
  }

  private val spiField = {
    val field = classOf[SSLContext].getDeclaredFields.find(_.getName == "contextSpi").get
    field.setAccessible(true)
    field
  }

  private [WrappedSSLContext] def getSpi(context: SSLContext): SSLContextSpi =
    spiField.get(context).asInstanceOf[SSLContextSpi]
}

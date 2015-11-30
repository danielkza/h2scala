package net.danielkza.http2.ssl

import java.lang.reflect.Method
import java.security.SecureRandom
import javax.net.ssl._

import scala.annotation.tailrec
import scala.collection.mutable

class WrappedSSLContext(context: SSLContext)
  extends SSLContext(new WrappedSSLContext.SpiWrapper(WrappedSSLContext.getSpi(context)), context.getProvider,
                     context.getProtocol)
{
  import WrappedSSLContext._

  getSpi(this).asInstanceOf[SpiWrapper].parent = this

  def mapEngine(engine: SSLEngine): SSLEngine =
    engine

  def mapSocketFactory(socketFactory: SSLSocketFactory): SSLSocketFactory =
    socketFactory

  def mapServerSocketFactory(serverSocketFactory: SSLServerSocketFactory): SSLServerSocketFactory =
    serverSocketFactory

  def mapSessionContext(context: SSLSessionContext): SSLSessionContext =
    context
}

object WrappedSSLContext {
  private[WrappedSSLContext] class SpiWrapper(val wrapped: SSLContextSpi, var parent: WrappedSSLContext = null)
    extends SSLContextSpi
  {
    private val methods = mutable.Map.empty[String, Method]

    @tailrec
    private def getDeclaredMethod(cls: Class[_], name: String, params: Class[_]*): Method = {
      try {
        return cls.getDeclaredMethod(name, params: _*)
      } catch { case e: NoSuchMethodException if cls.getSuperclass != null =>
        // pass
      }

      getDeclaredMethod(cls.getSuperclass, name, params: _*)
    }

    private def callMethod[T](name: String, params: Class[_]*)(actualParams: AnyRef*) = {
      methods.getOrElseUpdate(s"$name(${params.map(_.toString).mkString(",")})", {
        val m = getDeclaredMethod(wrapped.getClass, name, params: _*)
        m.setAccessible(true)
        m
      }).invoke(wrapped, actualParams: _*).asInstanceOf[T]
    }

    override def engineCreateSSLEngine(): SSLEngine = parent.mapEngine(
      callMethod[SSLEngine]("engineCreateSSLEngine")()
    )

    override def engineGetSocketFactory(): SSLSocketFactory = parent.mapSocketFactory(
      callMethod[SSLSocketFactory]("engineGetSocketFactory")()
    )

    override def engineInit(keyManagers: Array[KeyManager], trustManagers: Array[TrustManager],
                            secureRandom: SecureRandom): Unit =
    {
      callMethod[Unit]("engineInit", classOf[Array[KeyManager]], classOf[Array[TrustManager]], classOf[SecureRandom])(
                       keyManagers, trustManagers, secureRandom)
    }

    override def engineCreateSSLEngine(peerHost: String, peerPort: Int): SSLEngine = parent.mapEngine(
      callMethod[SSLEngine]("engineCreateSSLEngine", classOf[String], classOf[Int])(peerHost, Int.box(peerPort))
    )

    override def engineGetClientSessionContext(): SSLSessionContext = parent.mapSessionContext(
      callMethod[SSLSessionContext]("engineGetClientSessionContext")()
    )

    override def engineGetServerSessionContext(): SSLSessionContext = parent.mapSessionContext(
      callMethod[SSLSessionContext]("engineGetServerSessionContext")()
    )

    override def engineGetServerSocketFactory(): SSLServerSocketFactory = parent.mapServerSocketFactory(
      callMethod[SSLServerSocketFactory]("engineGetServerSocketFactory")()
    )
  }

  private val spiField = {
    val field = classOf[SSLContext].getDeclaredField("contextSpi")
    field.setAccessible(true)
    field
  }

  private [WrappedSSLContext] def getSpi(context: SSLContext): SSLContextSpi =
    spiField.get(context).asInstanceOf[SSLContextSpi]
}

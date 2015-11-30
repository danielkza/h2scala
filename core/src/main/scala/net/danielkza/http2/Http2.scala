package net.danielkza.http2

import java.net.InetSocketAddress
import scala.collection.immutable
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import com.typesafe.config.{Config, ConfigFactory}
import akka.actor.{ExtendedActorSystem, ExtensionId, ExtensionIdProvider, ActorSystem}
import akka.event.LoggingAdapter
import akka.stream.Materializer
import akka.stream.io._
import akka.stream.scaladsl._
import akka.http.ServerSettings
import akka.http.scaladsl.{Http, HttpsContext}
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{HttpResponse, HttpRequest}
import net.danielkza.http2.ssl.ALPNSSLContext
import net.danielkza.http2.model.Http2Response
import net.danielkza.http2.stream.ServerConnectionBlueprint

class Http2Ext(config: Config)(implicit system: ActorSystem) extends akka.actor.Extension {
  import Http2._

  private def sslTlsStage(httpsContext: Option[HttpsContext], role: Role, hostInfo: Option[(String, Int)] = None) =
    httpsContext match {
      case Some(hctx) => SslTls(new ALPNSSLContext(hctx.sslContext, immutable.Seq("h2")), hctx.firstSession, role,
                                hostInfo = hostInfo)
      case None       => SslTlsPlacebo.forScala
    }

  def bind(
    interface: String, port: Int = -1,
    settings: ServerSettings = ServerSettings(system),
    httpsContext: Option[HttpsContext] = None,
    log: LoggingAdapter = system.log,
    http2Settings: Http2Settings = Http2Settings(system))
   (implicit fm: Materializer)
    : Source[IncomingConnection, Future[ServerBinding]] =
  {
    val effectiveHttpsContext = httpsContext.getOrElse(Http().defaultClientHttpsContext)
    val effectivePort = if (port >= 0) port else 443
    val tlsStage = sslTlsStage(Some(effectiveHttpsContext), Server)
    val connections: Source[Tcp.IncomingConnection, Future[Tcp.ServerBinding]] =
      Tcp().bind(interface, effectivePort, settings.backlog, settings.socketOptions, halfClose = false, settings.timeouts.idleTimeout)

    connections.map { case Tcp.IncomingConnection(localAddress, remoteAddress, flow) =>
      val layer = ServerConnectionBlueprint(settings, http2Settings)
      IncomingConnection(localAddress, remoteAddress) { handler =>
        layer(handler).join(tlsStage.join(flow))
      }
    }.mapMaterializedValue {
      _.map(tcpBinding => ServerBinding(tcpBinding.localAddress)(() => tcpBinding.unbind()))(fm.executionContext)
    }
  }
}

object Http2 extends ExtensionId[Http2Ext] with ExtensionIdProvider {
  case class Http2Settings(
    maxIncomingStreams: Int,
    requestConcurrency: Int,
    maxOutgoingStreams: Int,
    incomingStreamFrameBufferSize: Int)

  object Http2Settings {
    private val defaults = ConfigFactory.parseMap(Map[String, AnyRef](
      "akka.http2.max-incoming-streams" -> Int.box(8),
      "akka.http2.request-concurrency" -> Int.box(8),
      "akka.http2.max-outgoing-streams" -> Int.box(8),
      "akka.http2.incoming-stream-frame-buffer-size" -> Int.box(32)
    ).asJava)

    def apply(config: Config): Http2Settings = {
      val c = config.withFallback(defaults)

      apply(
        maxIncomingStreams            = c.getInt("akka.http2.max-incoming-streams"),
        requestConcurrency            = c.getInt("akka.http2.request-concurrency"),
        maxOutgoingStreams            = c.getInt("akka.http2.max-outgoing-streams"),
        incomingStreamFrameBufferSize = c.getInt("akka.http2.incoming-stream-frame-buffer-size")
      )
    }

    def apply(system: ActorSystem): Http2Settings =
      apply(system.settings.config)
  }

  case class IncomingConnection
    (localAddress: InetSocketAddress, remoteAddress: InetSocketAddress)
    (private val flowGen: Flow[HttpRequest, Http2Response, Any] => RunnableGraph[Future[Unit]])
  {
    def handleWith(handler: Flow[HttpRequest, HttpResponse, Any])(implicit fm: Materializer): Future[Unit] =
      flowGen(handler.map(Http2Response.Simple)).run()

    def handleWith(handler: Flow[HttpRequest, Http2Response, Any])
                  (implicit fm: Materializer, dummyImplicit: DummyImplicit): Future[Unit] =
      flowGen(handler).run()

    def handleWithSyncHandler(handler: HttpRequest ⇒ HttpResponse)(implicit fm: Materializer): Unit =
      handleWith(Flow[HttpRequest].map { req => Http2Response.Simple(handler(req)) })

    def handleWithSyncHandler(handler: HttpRequest ⇒ Http2Response)
                             (implicit fm: Materializer, dummyImplicit: DummyImplicit): Unit =
      handleWith(Flow[HttpRequest].map(handler))

    def handleWithAsyncHandler(handler: HttpRequest ⇒ Future[HttpResponse])
                              (implicit fm: Materializer): Unit = {
      implicit val ec = fm.executionContext
      handleWith(Flow[HttpRequest].mapAsync(1) { req => handler(req).map(Http2Response.Simple) })
    }

    def handleWithAsyncHandler(handler: HttpRequest ⇒ Future[Http2Response])
                              (implicit fm: Materializer, dummyImplicit: DummyImplicit): Unit =
      handleWith(Flow[HttpRequest].mapAsync(1)(handler))
  }

  def apply()(implicit system: ActorSystem): Http2Ext = super.apply(system)

  def lookup() = Http2

  def createExtension(system: ExtendedActorSystem): Http2Ext = {
    val default = ConfigFactory.parseString("""akka.http2 {}""")
    val httpConfig = system.settings.config.getConfig("akka.http")
    val http2Config = system.settings.config.withFallback(default).withFallback(httpConfig).getConfig("akka.http2")
    new Http2Ext(http2Config.withFallback(http2Config))(system)
  }

  object Implicits {
    implicit class SourceWithRunServer[T](source: Source[T, Future[ServerBinding]]) {
      def runServerIndefinitely(system: ActorSystem)(implicit mat: Materializer, ec: ExecutionContext) = {
        val binding = source.toMat(Sink.ignore)(Keep.left).run()

        Runtime.getRuntime.addShutdownHook(new Thread(new Runnable() {
          override def run(): Unit = {
            Await.ready(binding.flatMap(_.unbind()).flatMap(_ => system.terminate()), Duration.Inf)
          }
        }))
      }
    }
  }
}

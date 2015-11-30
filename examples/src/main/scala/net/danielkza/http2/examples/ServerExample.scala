package net.danielkza.http2.examples

import java.io.{FileInputStream, File}
import java.nio.file.Files.probeContentType
import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import akka.stream._
import akka.stream.io._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.Directives._
import net.danielkza.http2.Http2
import net.danielkza.http2.Http2.Implicits._

import scala.concurrent.ExecutionContext

object ServerExample extends App {
  def createSSLContext: SSLContext = {
    val pass = System.getProperty("javax.net.ssl.keyStorePassword").toCharArray
    val keyStore = KeyStore.getInstance("jks")
    keyStore.load(new FileInputStream(System.getProperty("javax.net.ssl.keyStore")), pass)

    val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(keyStore, pass)

    val ctx = SSLContext.getInstance("TLSv1.2")
    ctx.init(keyManagerFactory.getKeyManagers, null, null)
    ctx
  }

  val config = ConfigFactory.parseString(
    """akka {
      |  stdout-loglevel = "DEBUG"
      |  loglevel = "DEBUG"
      |}
    """.stripMargin)

  implicit val actorSystem: ActorSystem = ActorSystem("ServerExample", config)
  val matSettings = ActorMaterializerSettings(actorSystem)
    .withDebugLogging(true)
    .withSupervisionStrategy { e: Throwable => println(e); Supervision.Stop }
  implicit val materializer: ActorMaterializer = ActorMaterializer(matSettings, "http2")
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  val sslContext = createSSLContext

  val routes: Route =
    path(RestPath) { path =>
      get {
        val file = new File("./" + path)
        if(file.isFile && file.canRead) {
          complete {
            val contentType = ContentType.parse(probeContentType(file.toPath)).right.get
            val source = HttpEntity(contentType, SynchronousFileSource(file))
            HttpResponse(StatusCodes.OK, entity = source)
          }
        } else {
          complete {
            (StatusCodes.NotFound, s"File `$path` not found")
          }
        }
      }
    }

  val binding = Http2().bind("0.0.0.0", port = 8080).map(
    _.handleWith(routes)
  ).runServerIndefinitely(actorSystem)
}

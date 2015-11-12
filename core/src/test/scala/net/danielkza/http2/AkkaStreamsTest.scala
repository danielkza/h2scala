package net.danielkza.http2

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializerSettings, ActorMaterializer}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.SpecificationLike

abstract class AkkaStreamsTest(
  _system: ActorSystem = ActorSystem("AkkaStreamTest", ConfigFactory.parseString(AkkaStreamsTest.config))
) extends AkkaTest(_system)
{ self: SpecificationLike =>
  implicit val actorMaterializer = ActorMaterializer(ActorMaterializerSettings(system))
}

object AkkaStreamsTest {
  val config = """
    akka {
      test {
        single-expect-default = 10 seconds
      }
    }
    """
}

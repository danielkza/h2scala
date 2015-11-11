package net.danielkza.http2

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializerSettings, ActorMaterializer}
import com.typesafe.config.ConfigFactory

abstract class AkkaStreamsTest(
  _system: ActorSystem = ActorSystem("AkkaStreamTest", ConfigFactory.parseString(AkkaStreamsTest.config))
) extends AkkaTest(_system)
{
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

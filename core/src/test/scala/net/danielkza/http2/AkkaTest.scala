package net.danielkza.http2

import org.specs2.specification.core._
import org.specs2.mutable.{After, SpecificationLike}
import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}

abstract class AkkaTest(_system: ActorSystem = ActorSystem("AkkaTest")) extends TestKit(_system) with ImplicitSender
  with SpecificationLike
{
  override def map(fs: => Fragments) = super.map(fs) ^ step(system.shutdown, global = true)
}

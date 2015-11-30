package net.danielkza.http2.util.stream

import akka.stream._

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable

class ConcentratorShape[-I, +O] private[http2] (
  override val inlets: immutable.Seq[Inlet[I @uncheckedVariance]],
  override val outlets: immutable.Seq[Outlet[O @uncheckedVariance]])
extends Shape
{
  def this(n: Int) =
    this(Vector.tabulate(n) { i => Inlet("Concentrator.in" + i)},
         Vector.tabulate(n) { i => Outlet("Concentrator.out" + i)})

  override def deepCopy(): ConcentratorShape[I, O] =
    new ConcentratorShape(inlets.map(_.carbonCopy()), outlets.map(_.carbonCopy()))

  override def copyFromPorts(inlets: immutable.Seq[Inlet[_]], outlets: immutable.Seq[Outlet[_]]): Shape = {
    require(inlets.nonEmpty, s"Empty inlets or outlets")
    require(inlets.size == outlets.size,
      s"Non-matching count of inlets [${inlets.mkString(", ")}] and outlets [${outlets.mkString(", ")}]")
    new ConcentratorShape(inlets.asInstanceOf[immutable.Seq[Inlet[I]]],
                          outlets.asInstanceOf[immutable.Seq[Outlet[O]]])
  }

  def in(i: Int): Inlet[I @uncheckedVariance] = inlets(i)
  def out(i: Int): Outlet[O @uncheckedVariance]= outlets(i)
  def flow(i: Int): FlowShape[I, O] = FlowShape(inlets(i), outlets(i))
}

package net.danielkza.http2.util

import java.util
import scala.reflect.ClassTag

class ArrayQueue[T <: AnyRef : ClassTag](val maxCapacity: Int) extends util.AbstractQueue[T] { self =>
  private val backing = Array.ofDim[T](maxCapacity)
  private var firstIndex = 0
  private var curSize = 0

  private def insertionIndex: Int =
    (firstIndex + curSize) % maxCapacity

  override def offer(e: T): Boolean = {
    if(size == maxCapacity)
      false
    else {
      backing(insertionIndex) = e
      curSize += 1
      true
    }
  }

  override def peek(): T = {
    if(size == 0)
      null.asInstanceOf[T]
    else
      backing(firstIndex)
  }

  override def poll(): T = {
    if(size == 0) {
      null.asInstanceOf[T]
    } else {
      val elm = backing(firstIndex)
      firstIndex = (firstIndex + 1) % maxCapacity
      curSize -= 1
      elm
    }
  }

  override def size(): Int = curSize

  def isFull: Boolean = size() == maxCapacity

  override def iterator(): util.Iterator[T] = new util.Iterator[T] {
    private val end = insertionIndex
    private var cur = firstIndex

    override def hasNext: Boolean = cur != end

    override def next(): T = {
      val elm = self.backing(cur)
      cur = (cur + 1) % maxCapacity
      elm
    }
  }
}

package net.danielkza.http2.hpack

import scala.collection.mutable
import akka.util.ByteString

trait Table extends {
  import Table.{FindResult, FoundName, FoundNameValue, NotFound}
  
  type Entry <: Table.Entry {type Self = Entry}
  def Entry(name: ByteString, value: ByteString): Entry

  def entries: collection.IndexedSeq[Entry]
  protected def entryMap: collection.Map[Entry, Int]
  def length: Int = entries.length
  
  @inline def get(index: Int): Option[Entry] = entries.lift(index - 1)
  
  def lookupEntry(entry: Entry): Option[Int] = entryMap.get(entry)
  
  def find(name: ByteString, value: Option[ByteString]): FindResult = {
    value flatMap { v =>
      lookupEntry(Entry(name, v)) match {
        case Some(index)     => Some(FoundNameValue(index))
        case _ if v.isEmpty  => Some(NotFound) // Don't lookup empty values twice
        case _               => None
      }
    } orElse {
      lookupEntry(Entry(name, ByteString.empty)).map(FoundName)
    } getOrElse {
      NotFound
    }
  }
}

object Table {
  sealed trait FindResult
  case class FoundName(index: Int) extends FindResult
  case class FoundNameValue(index: Int) extends FindResult
  case object NotFound extends FindResult
  
  trait Entry {
    type Self <: Entry
    def name: ByteString
    def value: ByteString
    def withEmptyValue: Self
  }
  
  def genEntryMap[E <: Entry {type Self = E}](entries: Traversable[E]): Traversable[(E, Int)] = {
    new Traversable[(E, Int)] {
      override def foreach[U](f: ((E, Int)) => U): Unit = {
        var i = 1
        entries.foreach { case entry =>
          f(entry -> i)
          f(entry.withEmptyValue -> i)
          i += 1
        }
      }
    }
  }
}

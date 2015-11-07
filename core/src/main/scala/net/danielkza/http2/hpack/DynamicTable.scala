package net.danielkza.http2.hpack

import scalaz._
import scalaz.syntax.either._
import akka.util.ByteString

class DynamicTable private (
  val maxCapacity: Int,
  val curCapacity: Int,
  val baseOffset: Int,
  val entries: Vector[DynamicTable.Entry],
  val curSize: Int,
  protected val entryMap: Map[DynamicTable.Entry, Int],
  protected val entryMapOffset: Int)
extends Table {
  final override type Entry = DynamicTable.Entry
  final override def Entry(name: ByteString, value: ByteString) = DynamicTable.Entry(name, value)
  
  def this(maxCapacity: Int, baseOffset: Int, entries: Traversable[DynamicTable.Entry] = Traversable.empty) = {
    this(maxCapacity, maxCapacity, baseOffset, entries.toVector, entries.foldLeft(0) { _ + _.size },
         Table.genEntryMap(entries).toMap, 0)
  }
  
  @inline override def get(index: Int) = entries.lift(index - baseOffset - 1)

  override def lookupEntry(entry: DynamicTable.Entry): Option[Int] = {
    entryMap.get(entry).flatMap { index =>
      index + entryMapOffset match {
        case i if i > baseOffset && i <= baseOffset + entries.size => Some(i)
        case _ => None
      }
    }
  }
  
  private def updated(newCapacity: Int = this.curCapacity, maxCapacity: Int = this.maxCapacity,
                      addEntries: Iterable[Entry] = Iterable.empty): DynamicTable = {
    var newSize = 0
    var numEntries = 0 
    
    def takeWhileNotFull(it: Iterator[Entry]) = {
      val prevNumEntries = numEntries
      it.takeWhile(newSize + _.size <= newCapacity).foreach { entry =>
        newSize += entry.size
        numEntries += 1
      }
      numEntries - prevNumEntries
    }
    
    // Calculate how many entries we'll need to take from the old and new list of entries until we fill the maximum size
    val numNewEntries = takeWhileNotFull(addEntries.iterator)
    val numOldEntries = takeWhileNotFull(entries.iterator)
    
    val newEntries = addEntries.take(numNewEntries).toVector
    val oldEntries = entries.take(numOldEntries)
    val mergedEntries = newEntries ++ oldEntries
    
    // Find out how much the old entries will be offset from their real indexes. Add new entries also with that same
    // offset, so we can reverse it uniformly while fetching.
    var newEntryMapOffset = entryMapOffset + numNewEntries
    
    val mergedMap = if(entryMap.size >= curCapacity / 2 || newEntryMapOffset >= curCapacity / 2) {
      // Rewrite thw whole map without an offset if enough entries are outdated
      newEntryMapOffset = 0
      Table.genEntryMap(mergedEntries).toMap      
    } else {
      // Apply the offsets to the new entries and merge
      val newMap = Table.genEntryMap(newEntries).map { case (entry, index) =>
        entry -> (index + baseOffset - newEntryMapOffset)
      }.toMap
      newMap ++ entryMap
    }

    new DynamicTable(maxCapacity, newCapacity, baseOffset, mergedEntries, newSize, mergedMap, newEntryMapOffset)
  }

  def withCapacity(capacity: Int): \/[HeaderError, DynamicTable] = {
    if(capacity > maxCapacity)
      HeaderError.DynamicTableCapacityExceeded.left
    else
      updated(newCapacity = capacity).right
  }
  
  def withMaxCapacity(maxCapacity: Int): DynamicTable =
    updated(maxCapacity = maxCapacity, newCapacity = Math.min(curCapacity, maxCapacity))
  
  def +(nv: (ByteString, ByteString)): DynamicTable =
    this ++ DynamicTable.Entry(nv._1, nv._2)
  
  def +(nv: (String, String))(implicit d1: DummyImplicit): DynamicTable =
    this ++ DynamicTable.Entry(ByteString(nv._1), ByteString(nv._2))
  
  def +(entry: Entry): DynamicTable =
    this ++ entry 
  
  def ++(entries: Entry*): DynamicTable =
    this ++ entries
  
  def ++(entries: Traversable[Entry]): DynamicTable =
    updated(addEntries = entries.toIterable)
}

object DynamicTable {
  final val ENTRY_OVERHEAD: Int = 32

  case class Entry(name: ByteString, value: ByteString) extends Table.Entry {
    override type Self = Entry
    override def withEmptyValue: Entry = copy(value = ByteString.empty)
    @inline def size = name.length + value.length + DynamicTable.ENTRY_OVERHEAD
  }
}

package net.danielkza.http2.hpack.coders

import akka.util.{ByteString, ByteStringBuilder}
import net.danielkza.http2.Coder
import net.danielkza.http2.hpack._

import scalaz._
import scalaz.syntax.either._
import scalaz.syntax.std.option._

class HeaderBlockCoder(maxCapacity: Int = 4096,
                       private val headerCoder: HeaderCoder = new HeaderCoder())
extends Coder[Seq[Header]] {
  override type Error = HeaderError
  
  private val staticTable = StaticTable.default
  private var dynamicTable: DynamicTable = new DynamicTable(maxCapacity, baseOffset = staticTable.length)

  def withCapacity(capacity: Int): \/[HeaderError, HeaderBlockCoder] =
    dynamicTable.withCapacity(capacity).map { dt => dynamicTable = dt; this }
  
  def withMaxCapacity(capacity: Int): HeaderBlockCoder = {
    dynamicTable = dynamicTable.withMaxCapacity(capacity)
    this
  }
  
  private def indexToEntry(index: Int): \/[HeaderError, Table.Entry] = {
    staticTable.get(index) orElse dynamicTable.get(index) match {
      case Some(entry) => \/-(entry)
      case _ => -\/(HeaderError.InvalidIndex(index))
    }
  }
  
  private def entryToIndex(name: ByteString, value: Option[ByteString]): Table.FindResult = {
    import Table.NotFound
    
    staticTable.find(name, value) match {
      case NotFound => dynamicTable.find(name, value)
      case r => r
    }
  }
  
  private def processHeader(header: Header): (HeaderRepr, DynamicTable) = {
    import Header._
    import HeaderRepr._
    import Table.{FoundName, FoundNameValue, NotFound}
    
    header match {
      case Plain(name, value) => entryToIndex(name, Some(value)) match {
        case FoundNameValue(index) =>
          Indexed(index) -> dynamicTable
        case FoundName(index) =>
          IncrementalLiteralWithIndexedName(index, value) -> (dynamicTable + (name -> value))
        case NotFound =>
          IncrementalLiteral(name, value) -> (dynamicTable + (name -> value))
      }
      case Secure(name, value) => entryToIndex(name, None) match {
        case FoundName(index) => NeverIndexedWithIndexedName(index, value) -> dynamicTable
        case NotFound         => NeverIndexed(name, value) -> dynamicTable
        case _                => throw new AssertionError("Secure header value should never be matched")
      }
    }    
  }
  
  private def processHeaderRepr(headerRepr: HeaderRepr)
    : \/[HeaderError, (Option[Header], DynamicTable)] =
  {
    import Header._
    import HeaderRepr._
    
    headerRepr match {
      case DynamicTableSizeUpdate(size) =>
        dynamicTable.withCapacity(size).map(None -> _)
      case Indexed(index) =>
        indexToEntry(index).map { e => Plain(e.name, e.value).some -> dynamicTable }
      case h @ IncrementalLiteralWithIndexedName(index, value) =>
        indexToEntry(index).map { e => Plain(e.name, value).some -> (dynamicTable + (e.name -> value)) }
      case h @ LiteralWithIndexedName(index, value) =>
        indexToEntry(index).map { e => Plain(e.name, value).some -> dynamicTable}
      case h @ NeverIndexedWithIndexedName(index, value) =>
        indexToEntry(index).map { e => Secure(e.name, value).some -> dynamicTable}
      case h @ IncrementalLiteral(name, value) =>
        (Plain(name, value).some -> (dynamicTable + (name -> value))).right
      case h @ Literal(name, value) =>
        (Plain(name, value).some -> dynamicTable).right
      case h @ NeverIndexed(name, value) =>
        (Secure(name, value).some -> dynamicTable).right
    }
  }
  
  override def decode(bs: ByteString): \/[HeaderError, (Seq[Header], Int)] = {    
    val headers = Seq.newBuilder[Header]
    var buffer = bs
    var totalBytes = 0
    do {
      (for {
        reprDec <- headerCoder.decode(buffer)
        (repr, bytesRead) = reprDec
        processed <- processHeaderRepr(repr)
        (maybeHeader, newDynamicTable) = processed
      } yield {
        maybeHeader.foreach { headers += _ }
        dynamicTable = newDynamicTable
        totalBytes += bytesRead
        buffer = buffer.drop(bytesRead)
      }) match {
        // match on left so right is inferred from the return type
        case -\/(e) => return -\/(e) 
        case _      => // pass
      }
    } while(buffer.nonEmpty)
    
    \/-(headers.result() -> totalBytes)
  }

  override def encode(headers: Seq[Header], stream: ByteStringBuilder): \/[HeaderError, Unit] = {
    headers.foreach { header =>
      val (repr, newDynamicTable) = processHeader(header)
      headerCoder.encode(repr, stream) match {
        case e @ -\/(_) => return e
        case _          => dynamicTable = newDynamicTable
      }
    }
    
    \/-(())
  }
}

package net.danielkza.http2.hpack


import scalaz._
import scalaz.std.AllInstances._
import scalaz.syntax.traverse._
import org.specs2.matcher.DataTables
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import akka.util.ByteString
import net.danielkza.http2.TestHelpers
import net.danielkza.http2.api.Header
import net.danielkza.http2.hpack.coders.{HeaderBlockCoder, HeaderCoder}

class HeaderBlockCoderTest extends Specification with DataTables with TestHelpers {
  import HeaderRepr._
  import Header.{plain, secure}
  
  def dynTablePos(x: Int) = StaticTable.default.length + x
  
  val (headers, reprs) = List(
    // fully indexed from static table
    plain (":status", "200"   ) -> Indexed(8),
    // name indexed from static table, dt-size = 1
    plain (":status", "999"   ) -> IncrementalLiteralWithIndexedName(14, bs"999"), 
    // new literal, dt-size = 2
    plain ("fruit",   "banana") -> IncrementalLiteral(bs"fruit", bs"banana"), 
    // new literal, dt-size = 3
    plain ("color",   "yellow") -> IncrementalLiteral(bs"color", bs"yellow"), 
    // repeat, fully indexed from dynamic table
    plain ("fruit",   "banana") -> Indexed(dynTablePos(2)), 
    // name indexed from dynamic table, dt-size = 4
    plain ("fruit",   "apple" ) -> IncrementalLiteralWithIndexedName(dynTablePos(2), bs"apple"),
    // repeat, fully indexed from dynamic table
    plain ("fruit",   "apple" ) -> Indexed(dynTablePos(1)),
    // repeat, fully indexed from dynamic table
    plain ("color",   "yellow") -> Indexed(dynTablePos(2)),
    // literal never indexed
    secure("drink",   "soda"  ) -> NeverIndexed(bs"drink", bs"soda"),
    // repeat literal never indexed, must not be in dynamic table
    secure("drink",   "soda"  ) -> NeverIndexed(bs"drink", bs"soda"),
    // literal never indexed, name indexed from dynamic table
    secure("color",   "blue"  ) -> NeverIndexedWithIndexedName(dynTablePos(2), bs"blue")
  ).unzip
  
  val headerCoder = new HeaderCoder(HeaderCoder.compress.Never)
  
  val encoded = {
    val parts: \/[HeaderError, List[ByteString]] = reprs.map(headerCoder.encode).sequenceU
    parts.map(_.reduce(_ ++ _)).getOrElse(throw new AssertionError)
  }
  
  trait Context extends Scope {
    val coder = new HeaderBlockCoder(headerCoder = headerCoder)
  }
  
  "HeaderBlockCoderTest" should {
    "encode" in {
      "a sequence of headers correctly" >> new Context {
        coder.encode(headers) must_== \/-(encoded)
      }
    }

    "decode" in {
      "a sequence of headers correctly" >> new Context {
        coder.decode(encoded) must_== \/-((headers, encoded.length))
      }
    }

    "withDynamicTableCapacity" in {
      ok
    }

  }
}

package net.danielkza.http2.hpack

import scalaz._

import org.specs2.matcher.DisjunctionMatchers
import org.specs2.mutable.Specification
import org.specs2.specification.Scope

import net.danielkza.http2.TestHelpers

class DynamicTableTest extends Specification with TestHelpers {
  trait Context extends Scope {
    var table = new DynamicTable(512, baseOffset=0)
  }

  "DynamicTableTest" should {
    "withCapacity" in {
      "should resize correctly" >> new Context {
        while(table.curSize <= 100)
          table += "Test" -> "Test"

        table.withCapacity(100) must beLike { case \/-(t) => t.curSize must be_<=(100) }
      }
      "should not resize over the max capacity" >> new Context {
        table.withCapacity(1000) must beLike { case -\/(e: HeaderError) => ok }
      }
    }

    "append" in {
      "should resize before insertion if necessary" >> new Context {
        val entry = DynamicTable.Entry(bs"Test1", bs"Test1")
        table.withCapacity(entry.size).map { table =>
          table + entry + ("Test2" -> "Test2")
        } must beLike { case \/-(t: DynamicTable) =>
          (t.entries must have size 1) and (t.entries.head.name === bs"Test2")
        }
      }
      "should leave the table empty if the new entry doesn't fit" >> new Context {
        table.withCapacity(1).map { table =>
          table + ("Test1" -> "Test1")
        } must beLike { case \/-(t: DynamicTable) =>
          t.entries must beEmpty
        }
      }
    }

    "find" in new Context {
      table += ("Test1" -> "Test1")
      table += ("Test2" -> "")
      table += ("Test1" -> "")

      "should find an exact match for name and value" >> {
        table.find(bs"Test1", Some(bs"")) === Table.FoundNameValue(3)
      }
      "should find a name match for name and value" >> {
        table.find(bs"Test1", Some(bs"Other")) === Table.FoundName(1)
      }
      "should find a match for name only" >> {
        table.find(bs"Test2", None) === Table.FoundName(2)
      }
      "should not find a match for name and value" >> {
        table.find(bs"Test3", Some(bs"")) === Table.NotFound
      }
      "should not find a match for name only" >> {
        table.find(bs"Test4", None) === Table.NotFound
      }
    }
  }
}

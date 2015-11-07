package net.danielkza.http2.hpack.coders

import scalaz.\/-
import org.specs2.matcher.DataTables
import org.specs2.mutable.Specification
import net.danielkza.http2.TestHelpers
import net.danielkza.http2.hpack.HeaderRepr._

class HeaderCoderTest extends Specification with DataTables with TestHelpers {
  val plainCoder = new HeaderCoder(HeaderCoder.compress.Never)
  val compressedCoder = new HeaderCoder(HeaderCoder.compress.Always)
  
  val plainCases = { "header" | "encoded" |>
    IncrementalLiteral("custom-key", "custom-header") !
    hex_bs"40 0a 6375 7374 6f6d 2d6b 6579 0d 6375 7374 6f6d 2d68 6561 6465 72"  |
    LiteralWithIndexedName(4, "/sample/path") !
    hex_bs"04 0c 2f73 616d 706c 652f 7061 7468 " |
    NeverIndexed("password", "secret") !
    hex_bs"10 08 7061 7373 776f 7264 06 7365 6372 6574" |
    Indexed(2) !
    hex_bs"82" |
    DynamicTableSizeUpdate(256) !
    hex_bs"3f e1 01"
  }
  
  val compressedCases = { "header" | "encoded" |>
    IncrementalLiteralWithIndexedName(1, "www.example.com") !
    hex_bs"41 8c f1e3 c2e5 f23a 6ba0 ab90 f4ff" |
    IncrementalLiteral("custom-key", "custom-value") !
    hex_bs"40 88 25a8 49e9 5ba9 7d7f 89 25a8 49e9 5bb8 e8b4 bf" |
    IncrementalLiteralWithIndexedName(33, "Mon, 21 Oct 2013 20:13:21 GMT") !
    hex_bs"61 96 d07a be94 1054 d444 a820 0595 040b 8166 e082 a62d 1bff" |
    IncrementalLiteralWithIndexedName(55, "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1") !
    hex_bs"""
    77 ad 94e7 821d d7f2 e6c7 b335 dfdf cd5b 3960 d5af 2708 7f36 72c1 ab27 0fb5 291f 9587 3160 65c0 03ed 4ee5 b106 3d50
    07"""
  }

  "HeaderCoder" should {
    "decode" in {
      "plain-text" in {
        plainCases | { (header, encoded) =>
          plainCoder.decode(encoded) must_== \/-((header, encoded.length))
        }
      }
      "compressed" in {
        compressedCases | { (header, encoded) =>
          val res = compressedCoder.decode(encoded)
          res must_== \/-((header, encoded.length))
        }
      }
    }
    "encode" in {
      "plain-text" in {
        plainCases | { (header, encoded) =>
          plainCoder.encode(header) must_== \/-(encoded)
        }
      }
      "compressed" in {
        compressedCases | { (header, encoded) =>
          val res = compressedCoder.encode(header)
          res must_== \/-(encoded)
        }
      }
    }
  }
}

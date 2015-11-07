package net.danielkza.http2.hpack.coders

import scalaz.\/-

import org.specs2.matcher.DataTables
import org.specs2.mutable.Specification
import net.danielkza.http2.TestHelpers

class BytesCoderTest extends Specification with DataTables with TestHelpers {
  val cases = {
    "result"          |  "input"                                  |>
    bs"custom-key"    ! hex_bs"0a 6375 7374 6f6d 2d6b 6579"         |
    bs"custom-header" ! hex_bs"0d 6375 7374 6f6d 2d68 6561 6465 72"
  }

  "BytesCoder" should {
    "decode" in {
      cases | { (result, input) =>
        new BytesCoder(0).decode(input) must_== \/-((result, input.length))
      }
    }

    "encode" in {
      cases | { (result, input) =>
        new BytesCoder(0).encode(result) must_== \/-(input)
      }
    }
  }
}

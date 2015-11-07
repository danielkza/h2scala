package net.danielkza.http2.hpack.coders

import scalaz.\/-

import org.specs2.matcher.DataTables
import org.specs2.mutable.Specification
import net.danielkza.http2.TestHelpers

class IntCoderTest extends Specification with DataTables with TestHelpers {
  val cases = {
    "elemSize"| "result"  | "input"                          |>
    5         ! 10        ! bits_bs"00001010"                   |
    5         ! 1337      ! bits_bs"00011111 10011010 00001010" |
    8         ! 42        ! bits_bs"00101010"
  }

  "IntCoder" should {
    "decode" in {
      cases | { (elmSize, result, input) =>
        new IntCoder(elmSize).decode(input) must_== \/-((result, input.length))
      }
    }

    "encode" in {
      cases | { (elmSize, result, input) =>
        new IntCoder(elmSize).encode(result) must_== \/-(input)
      }
    }
  }
}

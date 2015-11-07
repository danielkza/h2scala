package net.danielkza.http2.hpack.coders

import scalaz.\/-

import org.specs2.matcher.DataTables
import org.specs2.mutable.Specification
import net.danielkza.http2.TestHelpers

class CompressedBytesCoderTest extends Specification with DataTables with TestHelpers {
  val cases = {
    "plain"                | "encoded"                                    |>
    u8_bs"www.example.com" ! hex_bs"8c f1e3 c2e5 f23a 6ba0 ab90 f4ff"     |
    u8_bs"no-cache"        ! hex_bs"86 a8eb 1064 9cbf"                    |
    u8_bs"custom-key"      ! hex_bs"88 25a8 49e9 5ba9 7d7f"               |
    u8_bs"custom-value"    ! hex_bs"89 25a8 49e9 5bb8 e8b4 bf"
  }

  "CompressedBytesCoder" should {
    "decode" in {
      cases | { (plain, encoded) =>
        println("decoding " + plain.utf8String)
        (new CompressedBytesCoder).decode(encoded) must_== \/-((plain, encoded.length))
      }
    }

    "encode" in {
      cases | { (plain, encoded) =>
        (new CompressedBytesCoder).encode(plain) must_== \/-(encoded)
      }
    }
  }
}

package net.danielkza.http2.hpack.coders

import akka.util.ByteString

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
    u8_bs"custom-value"    ! hex_bs"89 25a8 49e9 5bb8 e8b4 bf"            |
    u8_bs"text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8" !
    ByteString(-72, 73, 124, -91, -119, -45, 77, 31, 67, -82, -70, 12, 65, -92, -57, -87, -113, 51, -90, -102, 63, -33, -102, 104, -6, 29, 117, -48, 98, 13, 38, 61, 76, 121, -90, -113, -66, -48, 1, 119, -2, -115, 72, -26, 43, 30, 11, 29, 127, 95, 44, 124, -3, -10, -128, 11, -67)
  }

  "CompressedBytesCoder" should {
    "decode" in {
      cases | { (plain, encoded) =>
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

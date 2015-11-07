package net.danielkza.http2.hpack.coders

import scala.language.experimental.macros
import scala.collection.immutable.HashMap

import scalaz.ImmutableArray

class HuffmanCoding(codes: (Byte, Short)*) {
  import HuffmanCoding.{Code, Symbol, EOS}
  
  final val (decodingTablesMap, encodingTable) = expandCodes(codes: _*)
  final val initialTable = decodingTablesMap(0)
  
  final type DecodingTable = IndexedSeq[Short]
  final type DecodingTableMap = HashMap[Int, DecodingTable]
  final type EncodingTable = IndexedSeq[Long]

  private def expandCodes(codes: (Byte, Short)*): (DecodingTableMap, EncodingTable) = {
    val decodingTableMap = HashMap.newBuilder[Int, DecodingTable]
    var curDecodingTable = ImmutableArray.newBuilder[Short]
    val encodingTable = new Array[Long](EOS + 1)

    var lastPrefix = 0
    var currentCode = 0
    var prevLength = 0
    var prevLengthBytes = 1
    
    codes.foreach { case (length, symbol) =>
      val lengthBytes = (length + 8 - 1) / 8
      
      // Only check for the need to create a new table if the code is larger than 8 bits. Otherwise it should always be
      // assigned to the initial table (since there's no previous byte to care about).
      if(lengthBytes > 1) {
        // Shift the code if we now use an extra byte
        if(lengthBytes > prevLengthBytes)
          currentCode <<= 8
        
        val currentPrefix = currentCode >>> 8
        if(currentPrefix != lastPrefix) {
          // The last byte has changed. Write out the just finished table to it's prefix and create a new one.
          decodingTableMap += Code.align(lastPrefix, prevLengthBytes * 8) -> curDecodingTable.result()
          curDecodingTable = ImmutableArray.newBuilder
          curDecodingTable.sizeHint(256)
          
          // Start out a new prefix and adjust the current code to match the correct length
          lastPrefix = currentPrefix
        }
      }
      
      encodingTable(symbol) = Code(currentCode, length)
      
      curDecodingTable += Symbol(symbol, length)
      currentCode += 1
    
      // If the current code doesn't fill whole bytes, add extra indexes representing it with all the possible values
      // of the unfilled bytes. For example, if we have a code ending with '000011', it's entries will be:
      // '000011-00', '000011-01', '000011-10', '000011-11' to match any whole byte that has it as a prefix.
      val remBits = (lengthBytes * 8) - length
      if(remBits > 0) {
        val lastCode = currentCode + (1 << remBits) - 1
        do {
          curDecodingTable += Symbol(symbol, length)
          currentCode += 1
        } while(currentCode != lastCode)
      }
      
      prevLength = length
      prevLengthBytes = lengthBytes
    }
    
    // The last table won't be added inside the loop since it break after the values run out
    decodingTableMap += lastPrefix -> curDecodingTable.result()
    
    (decodingTableMap.result(), ImmutableArray.make(encodingTable))
  }
  
  def decodingTableForCode(currentCode: Int): Option[DecodingTable] = {
    decodingTablesMap.get(currentCode)
  }
}
  
  
object HuffmanCoding {  
  final val EOS: Short = 256
    
  object Code {
    @inline def align(code: Int, length: Int): Int = {
      val lengthBytes = (length + 8 - 1) / 8
      lengthBytes match {
        case 1 => code << 24
        case 2 => code << 16
        case 3 => code << 8
        case _ => code
      }
    }
    
    @inline def apply(code: Int, length: Int): Long =
      (align(code, length) & 0xFFFFFFFFL) | (length & 0xFFFFFFFFL) << 32
    
    def unapply(l: Long): Option[(Int, Int)] =
      Some((l & 0xFFFFFFFFL).toInt, (l >>> 32).toInt & 0xFF)
  }

  object Symbol {
    @inline def apply(symbol: Short, length: Byte): Short = (symbol | (length << 9)).toShort
    def unapply(s: Short): Option[(Short, Byte)] =
      Some((s & 511).toShort, (s >>> 9).toByte)
  }
  
  final lazy val coding = new HuffmanCoding( 
    (5, 48),
    (5, 49),
    (5, 50),
    (5, 97),
    (5, 99),
    (5, 101),
    (5, 105),
    (5, 111),
    (5, 115),
    (5, 116),
    (6, 32),
    (6, 37),
    (6, 45),
    (6, 46),
    (6, 47),
    (6, 51),
    (6, 52),
    (6, 53),
    (6, 54),
    (6, 55),
    (6, 56),
    (6, 57),
    (6, 61),
    (6, 65),
    (6, 95),
    (6, 98),
    (6, 100),
    (6, 102),
    (6, 103),
    (6, 104),
    (6, 108),
    (6, 109),
    (6, 110),
    (6, 112),
    (6, 114),
    (6, 117),
    (7, 58),
    (7, 66),
    (7, 67),
    (7, 68),
    (7, 69),
    (7, 70),
    (7, 71),
    (7, 72),
    (7, 73),
    (7, 74),
    (7, 75),
    (7, 76),
    (7, 77),
    (7, 78),
    (7, 79),
    (7, 80),
    (7, 81),
    (7, 82),
    (7, 83),
    (7, 84),
    (7, 85),
    (7, 86),
    (7, 87),
    (7, 89),
    (7, 106),
    (7, 107),
    (7, 113),
    (7, 118),
    (7, 119),
    (7, 120),
    (7, 121),
    (7, 122),
    (8, 38),
    (8, 42),
    (8, 44),
    (8, 59),
    (8, 88),
    (8, 90),
    (10, 33),
    (10, 34),
    (10, 40),
    (10, 41),
    (10, 63),
    (11, 39),
    (11, 43),
    (11, 124),
    (12, 35),
    (12, 62),
    (13, 0),
    (13, 36),
    (13, 64),
    (13, 91),
    (13, 93),
    (13, 126),
    (14, 94),
    (14, 125),
    (15, 60),
    (15, 96),
    (15, 123),
    (19, 92),
    (19, 195),
    (19, 208),
    (20, 128),
    (20, 130),
    (20, 131),
    (20, 162),
    (20, 184),
    (20, 194),
    (20, 224),
    (20, 226),
    (21, 153),
    (21, 161),
    (21, 167),
    (21, 172),
    (21, 176),
    (21, 177),
    (21, 179),
    (21, 209),
    (21, 216),
    (21, 217),
    (21, 227),
    (21, 229),
    (21, 230),
    (22, 129),
    (22, 132),
    (22, 133),
    (22, 134),
    (22, 136),
    (22, 146),
    (22, 154),
    (22, 156),
    (22, 160),
    (22, 163),
    (22, 164),
    (22, 169),
    (22, 170),
    (22, 173),
    (22, 178),
    (22, 181),
    (22, 185),
    (22, 186),
    (22, 187),
    (22, 189),
    (22, 190),
    (22, 196),
    (22, 198),
    (22, 228),
    (22, 232),
    (22, 233),
    (23, 1),
    (23, 135),
    (23, 137),
    (23, 138),
    (23, 139),
    (23, 140),
    (23, 141),
    (23, 143),
    (23, 147),
    (23, 149),
    (23, 150),
    (23, 151),
    (23, 152),
    (23, 155),
    (23, 157),
    (23, 158),
    (23, 165),
    (23, 166),
    (23, 168),
    (23, 174),
    (23, 175),
    (23, 180),
    (23, 182),
    (23, 183),
    (23, 188),
    (23, 191),
    (23, 197),
    (23, 231),
    (23, 239),
    (24, 9),
    (24, 142),
    (24, 144),
    (24, 145),
    (24, 148),
    (24, 159),
    (24, 171),
    (24, 206),
    (24, 215),
    (24, 225),
    (24, 236),
    (24, 237),
    (25, 199),
    (25, 207),
    (25, 234),
    (25, 235),
    (26, 192),
    (26, 193),
    (26, 200),
    (26, 201),
    (26, 202),
    (26, 205),
    (26, 210),
    (26, 213),
    (26, 218),
    (26, 219),
    (26, 238),
    (26, 240),
    (26, 242),
    (26, 243),
    (26, 255),
    (27, 203),
    (27, 204),
    (27, 211),
    (27, 212),
    (27, 214),
    (27, 221),
    (27, 222),
    (27, 223),
    (27, 241),
    (27, 244),
    (27, 245),
    (27, 246),
    (27, 247),
    (27, 248),
    (27, 250),
    (27, 251),
    (27, 252),
    (27, 253),
    (27, 254),
    (28, 2),
    (28, 3),
    (28, 4),
    (28, 5),
    (28, 6),
    (28, 7),
    (28, 8),
    (28, 11),
    (28, 12),
    (28, 14),
    (28, 15),
    (28, 16),
    (28, 17),
    (28, 18),
    (28, 19),
    (28, 20),
    (28, 21),
    (28, 23),
    (28, 24),
    (28, 25),
    (28, 26),
    (28, 27),
    (28, 28),
    (28, 29),
    (28, 30),
    (28, 31),
    (28, 127),
    (28, 220),
    (28, 249),
    (30, 10),
    (30, 13),
    (30, 22),
    (30, 256)
  )
}

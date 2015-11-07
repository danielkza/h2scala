package net.danielkza.http2.hpack

import akka.util.ByteString

class StaticTable(initialEntries: (String, String)*) extends Table {  
  final override type Entry = StaticTable.Entry
  final override def Entry(name: ByteString, value: ByteString) = StaticTable.Entry(name, value)
  
  override val entries = initialEntries.map { case (name, value) =>
    Entry(ByteString(name), ByteString(value))
  }.toIndexedSeq
  
  override val entryMap = Table.genEntryMap[StaticTable.Entry](entries).toMap
}

object StaticTable {
  case class Entry(name: ByteString, value: ByteString) extends Table.Entry {
    override type Self = Entry
    override def withEmptyValue: Entry = copy(value = ByteString.empty)
  }
  
  lazy val default: StaticTable = new StaticTable(
    ":authority" -> "",
    ":method" -> "GET",
    ":method" -> "POST",
    ":path" -> "/",
    ":path" -> "/index.html",
    ":scheme" -> "http",
    ":scheme" -> "https",
    ":status" -> "200",
    ":status" -> "204",
    ":status" -> "206",
    ":status" -> "304",
    ":status" -> "400",
    ":status" -> "404",
    ":status" -> "500",
    "accept-charset" -> "",
    "accept-encoding" -> "gzip, deflate",
    "accept-language" -> "",
    "accept-ranges" -> "",
    "accept" -> "",
    "access-control-allow-origin" -> "",
    "age" -> "",
    "allow" -> "",
    "authorization" -> "",
    "cache-control" -> "",
    "content-disposition" -> "",
    "content-encoding" -> "",
    "content-language" -> "",
    "content-length" -> "",
    "content-location" -> "",
    "content-range" -> "",
    "content-type" -> "",
    "cookie" -> "",
    "date" -> "",
    "etag" -> "",
    "expect" -> "",
    "expires" -> "",
    "from" -> "",
    "host" -> "",
    "if-match" -> "",
    "if-modified-since" -> "",
    "if-match" -> "",
    "if-range" -> "",
    "if-unmodified-since" -> "",
    "last-modified" -> "",
    "link" -> "",
    "location" -> "",
    "max-forwards" -> "",
    "proxy-authenticate" -> "",
    "proxy-authorization" -> "",
    "range" -> "",
    "referer" -> "",
    "refresh" -> "",
    "retry-after" -> "",
    "server" -> "",
    "set-cookie" -> "",
    "strict-transport-security" -> "",
    "transfer-encoding" -> "",
    "user-agent" -> "",
    "vary" -> "",
    "via" -> "",
    "www-authenticate" -> ""
  )
}

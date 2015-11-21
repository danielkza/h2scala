package net.danielkza.http2.protocol

final case class Setting(identifier: Short, value: Int)

object Setting {
  object Identifiers {
    final val SETTINGS_HEADER_TABLE_SIZE: Short      = 0x1
    final val SETTINGS_ENABLE_PUSH: Short            = 0x2
    final val SETTINGS_MAX_CONCURRENT_STREAMS: Short = 0x3
    final val SETTINGS_INITIAL_WINDOW_SIZE: Short    = 0x4
    final val SETTINGS_MAX_FRAME_SIZE: Short         = 0x5
    final val SETTINGS_MAX_HEADER_LIST_SIZE: Short   = 0x6
  }

  import Identifiers._
  object Standard {
    def unapply(setting: Setting): Option[(Short, Int)] = {
      if(setting.identifier >= SETTINGS_HEADER_TABLE_SIZE && setting.identifier <= SETTINGS_MAX_HEADER_LIST_SIZE)
        Some(setting.identifier -> setting.value)
      else
        None
    }
  }

  object NonStandard {
    def unapply(setting: Setting): Option[(Short, Int)] = Standard.unapply(setting) match {
      case Some(_) => None
      case None => Some(setting.identifier -> setting.value)
    }
  }

  case class Extractor(identifier: Short) {
    def unapply(settings: List[Setting]): Option[Int] = {
      settings.find(_.identifier == identifier).map(_.value)
    }
  }

  final val ExtractHeaderTableSize      = Extractor(SETTINGS_HEADER_TABLE_SIZE)
  final val ExtractEnablePush           = Extractor(SETTINGS_ENABLE_PUSH)
  final val ExtractMaxConcurrentStreams = Extractor(SETTINGS_MAX_CONCURRENT_STREAMS)
  final val ExtractInitialWindowSize    = Extractor(SETTINGS_INITIAL_WINDOW_SIZE)
  final val ExtractMaxFrameSize         = Extractor(SETTINGS_MAX_FRAME_SIZE)
  final val ExtractMaxHeaderListSize    = Extractor(SETTINGS_MAX_HEADER_LIST_SIZE)
}

package net.danielkza.http2.protocol

sealed trait Setting {
  def identifier: Short
  def value: Int
}

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
  final case class Unknown(override val identifier: Short, override val value: Int) extends Setting
  final case class Standard(override val identifier: Short, override val value: Int) extends Setting

  private abstract class SettingCompanion(identifier: Short) {
    def apply(value: Int): Standard = Standard(identifier, value)
    def unapply(s: Standard): Option[(Short, Int)] = s match {
      case Standard(`identifier`, value) => Some(identifier -> value)
      case _ => None
    }
  }

  object HeaderTableSize extends SettingCompanion(SETTINGS_HEADER_TABLE_SIZE)
  object MaxConcurrentStreams extends SettingCompanion(SETTINGS_MAX_CONCURRENT_STREAMS)
  object InitialWindowSize extends SettingCompanion(SETTINGS_INITIAL_WINDOW_SIZE)
  object MaxFrameSize extends SettingCompanion(SETTINGS_MAX_FRAME_SIZE)
  object MaxHeaderListSize extends SettingCompanion(SETTINGS_MAX_HEADER_LIST_SIZE)
  object EnablePush {
    def apply(value: Int): Standard = Standard(SETTINGS_ENABLE_PUSH, value)
    def unapply(s: Standard): Option[(Short, Boolean)] = s match {
      case Standard(i @ SETTINGS_ENABLE_PUSH, value) => Some(i -> (value != 0))
      case _ => None
    }
  }
}

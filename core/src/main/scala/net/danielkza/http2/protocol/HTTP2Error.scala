package net.danielkza.http2.protocol

import scala.language.implicitConversions
import akka.util.ByteString
import akka.http.scaladsl.model.ErrorInfo

trait HTTP2Error {
  def code: Int
  def debugData: Option[ByteString]
  def errorInfo: Option[ErrorInfo]

  final def toException(message: String = null, cause: Throwable = null): HTTP2Exception = {
    val formattedMessage = (errorInfo, Option(message)) match {
      case (Some(error), Some(msg)) => s"$msg: ${error.summary}"
      case (Some(error), None)      => error.summary
      case (None, Some(msg))        => msg
      case (None, None)             => "Unknown error"
    }

    try {
      throw new HTTP2Exception(this)(getClass.getSimpleName + ": " + formattedMessage, cause)
    } catch { case e: HTTP2Exception => e }
  }

  final def toException: HTTP2Exception = toException()
}

case class HTTP2Exception(error: HTTP2Error)(message: String = null, cause: Throwable = null)
  extends Exception(message, cause)

object HTTP2Error {
  object Codes {
    final val NO_ERROR            = 0x0
    final val PROTOCOL_ERROR      = 0x1
    final val INTERNAL_ERROR      = 0x2
    final val FLOW_CONTROL_ERROR  = 0x3
    final val SETTINGS_TIMEOUT    = 0x4
    final val STREAM_CLOSED       = 0x5
    final val FRAME_SIZE_ERROR    = 0x6
    final val REFUSED_STREAM      = 0x7
    final val CANCEL              = 0x8
    final val COMPRESSION_ERROR   = 0x9
    final val CONNECT_ERROR       = 0xa
    final val ENHANCE_YOUR_CALM   = 0xb
    final val INADEQUATE_SECURITY = 0xc
    final val HTTP_1_1_REQUIRED   = 0xd
  }
  import Codes._

  implicit def toException(error: HTTP2Error): HTTP2Exception = error.toException

  sealed abstract class Standard(override val code: Int) extends HTTP2Error {
    type Self <: HTTP2Error
    def withDebugData(debugData: Option[ByteString]): Self
    def withErrorInfo(errorInfo: Option[ErrorInfo]): Self
    def withErrorMessage(message: String): Self = withErrorInfo(Some(ErrorInfo(message)))
  }

  private case class GenericStandard(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
                                    (errorCode: Int) extends Standard(errorCode) {
    override type Self = GenericStandard

    override def withDebugData(debugData: Option[ByteString]): Self = copy(debugData = debugData)(errorCode)
    override def withErrorInfo(errorInfo: Option[ErrorInfo]): Self = copy(errorInfo = errorInfo)(errorCode)
  }

  object Standard {
    def unapply(e: HTTP2Error): Option[(Int, Option[ErrorInfo], Option[ByteString])] = {
      e match {
        case s: Standard => Some(s.code, s.errorInfo, s.debugData)
        case _ => None
      }
    }

    def fromCode(errorCode: Int): HTTP2Error = {
      errorCode match {
        case NO_ERROR           => NoError()
        case PROTOCOL_ERROR     => ProtocolError()
        case INTERNAL_ERROR     => InternalError()
        case FLOW_CONTROL_ERROR => FlowControlError()
        case STREAM_CLOSED      => StreamClosedError()
        case FRAME_SIZE_ERROR   => InvalidFrameSize()
        case REFUSED_STREAM     => RefusedStream()
        case COMPRESSION_ERROR  => CompressionError()
        case SETTINGS_TIMEOUT   => SettingsTimeout()
        case e                  => GenericStandard()(e)
      }
    }
  }

  implicit def stringToErrorInfo(message: String): Option[ErrorInfo]
    = Some(ErrorInfo(message))

  case class NoError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(NO_ERROR)
  {
    final type Self = NoError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class ProtocolError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
      extends Standard(PROTOCOL_ERROR)
    {
      final type Self = ProtocolError
      def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
      def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
    }

  case class InvalidStream(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = InvalidStream
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class InvalidFrameSize(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(FRAME_SIZE_ERROR)
  {
    final type Self = InvalidFrameSize
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class InvalidWindowUpdate(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = InvalidWindowUpdate
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class InvalidPadding(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = InvalidPadding
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class ContinuationError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = ContinuationError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class CompressionError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(COMPRESSION_ERROR)
  {
    final type Self = CompressionError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class HeaderError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = HeaderError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class SettingsError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = SettingsError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class SettingsTimeout(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(SETTINGS_TIMEOUT)
  {
    final type Self = SettingsTimeout
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class StreamClosedError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(STREAM_CLOSED)
  {
    final type Self = StreamClosedError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class UnacceptableFrameError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(PROTOCOL_ERROR)
  {
    final type Self = UnacceptableFrameError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class ExhaustedStreams(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(NO_ERROR)
  {
    final type Self = ExhaustedStreams
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class RefusedStream(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(REFUSED_STREAM)
  {
    final type Self = RefusedStream
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class InternalError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(INTERNAL_ERROR)
  {
    final type Self = InternalError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  case class FlowControlError(errorInfo: Option[ErrorInfo] = None, debugData: Option[ByteString] = None)
    extends Standard(FLOW_CONTROL_ERROR)
  {
    final type Self = FlowControlError
    def withDebugData(debugData: Option[ByteString]) = copy(debugData = debugData)
    def withErrorInfo(errorInfo: Option[ErrorInfo]) = copy(errorInfo = errorInfo)
  }

  object NonStandard {
    def unapply(e: HTTP2Error): Option[(Int, Option[ErrorInfo], Option[ByteString])] = {
      Standard.unapply(e) match {
        case Some(_) => None
        case None => Some(e.code, e.errorInfo, e.debugData)
      }
    }
  }
}

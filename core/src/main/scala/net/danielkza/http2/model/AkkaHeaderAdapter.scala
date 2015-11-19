package net.danielkza.http2.model

import scala.collection.immutable
import scalaz._
import scalaz.syntax.either._
import scalaz.syntax.std.option._
import akka.util.ByteString
import akka.http.ClientConnectionSettings
import akka.http.scaladsl.{model => akkaModel}
import net.danielkza.http2.api.Header
import net.danielkza.http2.protocol.HTTP2Error

class AkkaHeaderAdapter(settings: ClientConnectionSettings) {
  import Header._
  import Header.PseudoHeaders._
  import akkaModel._
  import settings._

  private def headerErr(info: ErrorInfo): HTTP2Error.HeaderError =
    HTTP2Error.HeaderError(errorInfo = Some(info))

  private def headerErr(summary: String): HTTP2Error.HeaderError =
    headerErr(ErrorInfo(summary))

  def headersFromAkkaUri(akkaUri: Uri): \/[HTTP2Error, Seq[Header]] = {
    val scheme = akkaUri.scheme
    val authority = if(!akkaUri.authority.isEmpty) {
      if(akkaUri.authority.userinfo.nonEmpty)
        return headerErr("Userinfo not allowed in :authority").left
      else
        ByteString(akkaUri.authority.toString)
    } else {
      ByteString.empty
    }

    val target = ByteString(akkaUri.toHttpRequestTargetOriginForm.toString)
    var headers = Seq(RawHeader(SCHEME, ByteString(scheme)), RawHeader(PATH, target))
    if(authority.nonEmpty) headers = headers :+ RawHeader(AUTHORITY, authority)

    headers.right
  }

  def headersFromAkkaMessage(message: HttpMessage): \/[HTTP2Error, Seq[Header]] = {
    message match {
      case req: HttpRequest =>
        for {
          uriHeaders <- headersFromAkkaUri(req.uri)
          method = WrappedAkkaMethod(req.method)
          otherHeaders = req.headers.map(WrappedAkkaHeader(_))
        } yield (uriHeaders :+ method) ++ otherHeaders
      case resp: HttpResponse =>
        (WrappedAkkaStatusCode(resp.status) +: resp.headers.map(WrappedAkkaHeader(_))).right
    }
  }

  private def decodeBytes(bs: ByteString): String =
    bs.decodeString("UTF-8")

  def parseHostAuthority(name: String, value: String) = {
    try {
      HttpHeader.parse("Host", value) match {
        case HttpHeader.ParsingResult.Ok(akkaModel.headers.Host(uriHost, port), _) =>
          Uri.Authority(uriHost, port).right
        case HttpHeader.ParsingResult.Error(error) =>
          throw IllegalUriException(error)
        case _ =>
          throw IllegalUriException("Bad host value")

      }
    } catch { case e: IllegalUriException =>
      headerErr(e.info.withSummaryPrepended(s"Invalid $name")).left
    }
  }

  def headersToAkkaRequest(headers: Seq[Header]): \/[HTTP2Error, HttpRequest] = {
    var scheme: Option[String] = None
    var authority: Option[String] = None
    var path: Option[String] = None
    var host: Option[String] = None
    var method: Option[HttpMethod] = None
    val akkaHeaders = immutable.Seq.newBuilder[HttpHeader]

    headers.foreach { header =>
      header.name match {
        case STATUS =>
          return headerErr("Status not allowed in request").left
        case SCHEME if scheme.isDefined =>
          return headerErr("Scheme redefined").left
        case SCHEME =>
          scheme = decodeBytes(header.value).some
        case METHOD if method.isDefined =>
          return headerErr("Method redefined").left
        case METHOD =>
          val stringValue = decodeBytes(header.value)
          method = HttpMethods.getForKey(stringValue).orElse {
            HttpMethod.custom(stringValue).some
          }
        case PATH if path.isDefined =>
          return headerErr("Path redefined").left
        case PATH =>
          path = decodeBytes(header.value).some
        case AUTHORITY if authority.isDefined =>
          return headerErr("Authority redefined").left
        case AUTHORITY =>
          authority = decodeBytes(header.value).some
        case HOST if host.isDefined =>
          return headerErr("Host redefined").left
        case HOST =>
          host = decodeBytes(header.value).some
        case _ =>
          HttpHeader.parse(decodeBytes(header.name), decodeBytes(header.value)) match {
            case HttpHeader.ParsingResult.Error(error) =>
              return headerErr(error).left
            case HttpHeader.ParsingResult.Ok(akkaHeader, _) =>
              akkaHeaders += akkaHeader
          }
      }
    }

    for {
      schemeVal <- scheme map(_.right) getOrElse
        headerErr("Scheme must not be empty in request").left
      pathVal   <- path map(_.right) getOrElse
        headerErr("Path must not be empty in request").left
      methodVal <- method map(_.right) getOrElse
        headerErr("Method must not be empty in request").left
      authorityVal <- (authority, host) match {
        case (Some(_), Some(_)) =>
          headerErr("Cannot have :authority and Host headers simultaneously").left
        case (Some(auth), _) =>
          parseHostAuthority(":authority", auth)
        case (_, Some(hostHeader)) =>
          parseHostAuthority("Host", hostHeader)
        case _ =>
          Uri.Authority.Empty.right
      }
      uri <- try {
        val pathUri = Uri.parseHttpRequestTarget(pathVal, mode = parserSettings.uriParsingMode)
        pathUri.withScheme(schemeVal).withAuthority(authorityVal).right
      } catch { case IllegalUriException(error) =>
        headerErr(error.withSummaryPrepended("Invalid request target")).left
      }
    } yield HttpRequest(methodVal, uri, akkaHeaders.result())
  }

  def parseStatus(value: ByteString): \/[HTTP2Error, StatusCode] = {
    val status = try {
      val code = decodeBytes(value).toInt
      StatusCodes.getForKey(code).orElse(parserSettings.customStatusCodes(code))
    } catch { case _: NumberFormatException =>
      None
    }

    status.map(_.right).getOrElse(-\/(headerErr("Invalid status code")))
  }

  def headersToAkkaResponse(headers: Seq[Header]): \/[HTTP2Error, HttpResponse] = {
    var status: Option[StatusCode] = None
    val akkaHeaders = immutable.Seq.newBuilder[HttpHeader]

    headers.foreach { header =>
      header.name match {
        case STATUS if status.isDefined =>
          return headerErr("Status redefined").left
        case STATUS =>
          status = parseStatus(header.value) match {
            case -\/(error)  => return error.left
            case \/-(statusCode) => statusCode.some
          }
        case SCHEME =>
          return headerErr(":status not allowed in response").left
        case METHOD =>
          return headerErr(":method not allowed in response").left
        case PATH =>
          return headerErr(":path not allowed in response").left
        case AUTHORITY =>
          return headerErr(":authority not allowed in response").left
        case HOST =>
          return headerErr("Host not allowed in response").left
        case _ =>
          HttpHeader.parse(decodeBytes(header.name), decodeBytes(header.value)) match {
            case HttpHeader.ParsingResult.Ok(akkaHeader, _) => akkaHeaders += akkaHeader
            case HttpHeader.ParsingResult.Error(error) => return headerErr(error).left
          }
      }
    }

    status map { status =>
      HttpResponse(status, akkaHeaders.result()).right
    } getOrElse {
      headerErr("Status must not be empty in response").left
    }
  }
}

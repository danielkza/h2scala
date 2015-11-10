package net.danielkza.http2.protocol.coders

import scalaz._
import scalaz.std.AllInstances._
import scalaz.syntax.traverse._
import akka.util.ByteString
import argonaut._
import Argonaut._
import better.files._
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragments
import net.danielkza.http2.TestHelpers
import net.danielkza.http2.protocol.{Setting, Frame}

private class StreamIgnoringFrameCoder extends FrameCoder(0) {
  override protected def checkTargetStream(stream: Int) = true
}

class FrameCoderTest extends Specification with TestHelpers {
  sealed trait Case {
    def wire: ByteString
    def description: String
  }
  case class OkCase(wire: ByteString, description: String, length: Int, flags: Byte, stream: Int, tpe: Byte,
                    payload: Frame) extends Case
  case class ErrorCase(wire: ByteString, description: String, errors: List[Int]) extends Case
  
  implicit def bsJson: DecodeJson[ByteString] =
    DecodeJson(c => c.as[String].map(ByteString(_)))
  
  def frameJson(tpe: Byte): DecodeJson[Frame] = DecodeJson(c => {
    import Frame._
    tpe match {
      case Types.DATA => for {
        padLen  <- c.get[Option[Int]]("padding_length")
        data    <- c.get[ByteString]("data")
        padding <- c.get[Option[ByteString]]("padding")
      } yield Data(data, padding = padding)
      
      case Types.HEADERS => for {
        padLen    <- c.get[Option[Int]]("padding_length")
        stream    <- c.get[Option[Int]]("stream_dependency")
        exclusive <- c.get[Option[Boolean]]("exclusive")
        weight    <- c.get[Option[Int]]("weight")
        frag      <- c.get[ByteString]("header_block_fragment")
        padding   <- c.get[Option[ByteString]]("padding")
      } yield {
        val streamDependency = stream.map { s => StreamDependency(exclusive.get, s, weight.get.toByte) }
        Headers(streamDependency, frag, padding = padding)
      }
      
      case Types.PRIORITY => for {
        stream    <- c.get[Int]("stream_dependency")
        weight    <- c.get[Int]("weight")
        exclusive <- c.get[Boolean]("exclusive")
      } yield Priority(StreamDependency(exclusive, stream, weight.toByte))
      
      case Types.RST_STREAM => for {
        error <- c.get[Int]("error_code")
      } yield ResetStream(error)
      
      case Types.SETTINGS => for {
        settings <- c.get[List[(Int, Int)]]("settings")
      } yield Settings(settings.map(t => Setting(t._1.toShort, t._2)))
      
      case Types.PUSH_PROMISE => for {
        padLen  <- c.get[Option[Int]]("padding_length")
        stream  <- c.get[Int]("promised_stream_id")
        frag    <- c.get[ByteString]("header_block_fragment")
        padding <- c.get[Option[ByteString]]("padding")
      } yield PushPromise(stream, frag, padding = padding)
      
      case Types.PING => for {
        data <- c.get[ByteString]("opaque_data")
      } yield Ping(data)
        
      case Types.GOAWAY => for {
        stream    <- c.get[Int]("last_stream_id")
        error     <- c.get[Int]("error_code")
        debugData <- c.get[ByteString]("additional_debug_data")
      } yield GoAway(stream ,error, debugData)
      
      case Types.CONTINUATION => for {
        frag <- c.get[ByteString]("header_block_fragment")
      } yield Continuation(frag)
      
      case Types.WINDOW_UPDATE => for {
        increment <- c.get[Int]("window_size_increment")
      } yield WindowUpdate(increment)

      case _ =>
        DecodeResult.fail(s"Unknown frame type $tpe", c.history)
    }
  })
  
  implicit def caseJson: DecodeJson[Case] = DecodeJson(c => for {
    wire        <- (c --\ "wire").as[String].map(_.byteStringFromHex)
    description <- (c --\ "description").as[String]
    result <- (c --\ "error").as[List[Int]].map { errorList =>
      ErrorCase(wire, description, errorList): Case
    } ||| {
      for {
        length  <- (c --\ "frame" --\ "length").as[Int]
        flags   <- (c --\ "frame" --\ "flags").as[Int].map(_.toByte)
        stream  <- (c --\ "frame" --\ "stream_identifier").as[Int]
        tpe     <- (c --\ "frame" --\ "type").as[Int].map(_.toByte)
        payload <- frameJson(tpe.toByte).tryDecode(c --\ "frame" --\ "frame_payload").map(_.withFlags(flags))
      } yield OkCase(wire, description, length, flags.toByte, stream, tpe, payload): Case
    }
  } yield result)

  def readCases(): \/[String, List[Case]] = {
    for {
      caseDirectory <- sys.props.get("http2.frame_tests_dir").map(\/-(_)).getOrElse {
        -\/("Failed to find HTTP2 Frame Test Cases. Make sure the `http2.frame_tests_dir` system property is correct")
      }
      val files = caseDirectory.toFile.listRecursively.filter(_.name.endsWith(".json")).toList
      results <- files.map { file =>
        Parse.decodeValidation[Case](file.contentAsString).disjunction.leftMap { error =>
          file.fullPath + ": " + error
        }
      }.sequence[\/[String, ?], Case]
    } yield results
  }
  
  "FrameCoder" should {
    val cases = readCases()
    
    cases map { cases =>
      "encode" in Fragments.foreach(cases) {
        case c: OkCase => describe(c.description) >> {
          new FrameCoder(c.stream).encode(c.payload) must_== \/-(c.wire)
        }
        case _ =>
          Fragments.empty
      }
    } valueOr { error =>
      Fragments { "encode" in skipped("Error: " + error) }
    }
        
    cases map { cases =>
      val errorCoder = new StreamIgnoringFrameCoder

      "decode" in Fragments.foreach(cases) {
        case c: OkCase => describe(c.description) >> {
          new FrameCoder(c.stream).decodeS.eval(c.wire) must beLike { case \/-(frame) =>
            (frame must_== c.payload) and (frame.flags must_== c.flags)
          }
        }
        case c: ErrorCase => describe(c.description) >> {
          errorCoder.decodeS.eval(c.wire) must beLike { case -\/(error) =>
            c.errors must contain(error.code)
          }
        }
      }
    } valueOr { error =>
      Fragments { "decode" in skipped("Error: " + error) }
    }
  }
}


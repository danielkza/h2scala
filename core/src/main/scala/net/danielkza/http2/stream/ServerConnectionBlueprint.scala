package net.danielkza.http2.stream

import akka.stream.stage.{SyncDirective, Context, PushStage}
import net.danielkza.http2.stream.StreamManagerActor.{LastProcessedStream, GetLastProcessedStream, StreamProcessingStarted}

import scala.collection.immutable
import scala.util.{Success, Failure}
import scala.concurrent.{Await, Promise, ExecutionContext, Future}
import scala.concurrent.duration._
import scalaz.syntax.either._
import akka.util.{ByteString, Timeout}
import akka.event.Logging
import akka.actor.{PoisonPill, ActorSystem}
import akka.pattern.ask
import akka.stream._
import akka.stream.io._
import akka.stream.scaladsl._
import akka.http.ServerSettings
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Content-Type`, `Transfer-Encoding`, `Content-Length`}
import akka.http.scaladsl.model.HttpEntity.{LastChunk, ChunkStreamPart}
import net.danielkza.http2.util.Implicits._
import net.danielkza.http2.api.Header
import net.danielkza.http2.model._
import net.danielkza.http2.model.headers.Trailer
import net.danielkza.http2.protocol._
import net.danielkza.http2.Http2.Http2Settings

object ServerConnectionBlueprint {
  import Frame._
  import HTTP2Error._
  import Setting.Identifiers._

  private def decodeDataNormal: Flow[Frame, ByteString, Any] =
    Flow[Frame] transform (() => new NormalDataDecodeStage(ignoreNonData = true))

  private def mkRequestBuilder(akkaAdapter: AkkaMessageAdapter, headerDecodeFlow: Flow[Frame, Seq[Header], Any])
    : Flow[Frame, (HttpRequest, Int), Any] =
  {
    Flow.fromGraph(FlowGraph.create(headerDecodeFlow) { implicit b => headerDecode =>
      import FlowGraph.Implicits._

      val headersAndRest = b.add(
        Flow[Frame].filter { f =>
          f.isInstanceOf[Headers] || f.isInstanceOf[Data] || f.isInstanceOf[PushPromise]
        }.splitAfter {
          case h: Headers      => true
          case _               => false
        }.via(headAndTailFlow).map {
          case (h: Headers, rest) => (h, rest)
          case (f: Frame, rest)   => throw new UnacceptableFrameError(s"Got $f instead of Headers")
        }
      )

      val decChunked = Flow.fromGraph(FlowGraph.create() { implicit b =>
        val chunker = b.add(new ChunkedDataDecodeStage(trailers = false))
        chunker.out1 ~> Sink.ignore

        FlowShape(chunker.in, chunker.out0)
      })

      val decTrailers = Flow.fromGraph(FlowGraph.create(headerDecodeFlow) { implicit b => headerDecode =>
        import FlowGraph.Implicits._

        val chunker = b.add(new ChunkedDataDecodeStage(trailers = true))
        val combiner = b.add(Concat[ChunkStreamPart](2))
        val trailingChunker = b.add(Flow[Seq[Header]] map { headers =>
          akkaAdapter.headersToAkka(headers).map(hs => LastChunk(trailer = hs)).orThrow
        })

        chunker.out0 ~> combiner.in(0)
        chunker.out1 ~> headerDecode ~> trailingChunker ~> combiner.in(1)

        FlowShape(chunker.in, combiner.out)
      })

      val genRequest = b.add(ZipWith { (headers: Seq[Header], data: Source[Frame, Any]) =>
        akkaAdapter.headersToAkkaRequest(headers).flatMap { request =>
          val trailers = TransferEncodings.Extension("trailers")
          var enc: Option[Seq[TransferEncoding]] = None
          var len: Option[Long] = None
          var tpe: Option[ContentType] = None

          request.headers.foreach {
            case `Transfer-Encoding`(e) => enc.map(throw HeaderError()).getOrElse(enc = Some(e))
            case `Content-Length`(l)    => len.map(throw HeaderError()).getOrElse(len = Some(l))
            case `Content-Type`(t)      => tpe.map(throw HeaderError()).getOrElse(tpe = Some(t))
            case _ =>
          }

          val withTrailers = enc match {
            case Some(Seq(`trailers`)) => true
            case None                  => request.headers.exists(_.isInstanceOf[Trailer])
          }

          val actualType = tpe.getOrElse(ContentTypes.`application/octet-stream`)
          val entity = if(withTrailers)
            HttpEntity.Chunked(actualType, data.via(decTrailers))
          else {
            len match {
              case Some(0) =>
                HttpEntity.Empty
              case Some(l) =>
                HttpEntity.Default(actualType, l, data.via(decodeDataNormal))
              case _ =>
                val chunkedData = data.via(decChunked)
                HttpEntity.Chunked(actualType, chunkedData)
            }
          }

          request.withEntity(entity).right
        }.orThrow
      })

      val splitter = b.add(
        UnzipWith { t: (Headers, Source[Frame, Any]) => (t._1, t._2, t._1.stream) }
      )
      val combiner = b.add(Zip[HttpRequest, Int])

      headersAndRest ~> splitter.in
                        splitter.out0 ~> headerDecode ~> genRequest.in0
                        splitter.out1                 ~> genRequest.in1
                                                         genRequest.out ~> combiner.in0
                        splitter.out2                                   ~> combiner.in1

      FlowShape(headersAndRest.inlet, combiner.out)
    })
  }

  def apply(serverSettings: ServerSettings, http2Settings: Http2Settings)
           (implicit actorSystem: ActorSystem)
    : Flow[HttpRequest, Http2Response, Any] => Flow[SslTlsInbound, SslTlsOutbound, Future[Unit]] =
  {
    apply(serverSettings, http2Settings.maxIncomingStreams, http2Settings.maxOutgoingStreams,
          http2Settings.incomingStreamFrameBufferSize)
  }

  def apply(serverSettings: ServerSettings, maxIncomingStreams: Int, maxOutgoingStreams: Int,
            incomingStreamBufferSize: Int)
           (handler: Flow[HttpRequest, Http2Response, Any])
           (implicit actorSystem: ActorSystem)
    : Flow[SslTlsInbound, SslTlsOutbound, Future[Unit]] =
  {
    val akkaAdapter = new AkkaMessageAdapter(serverSettings.parserSettings)
    val headerDecodeActor = actorSystem.actorOf(HeaderDecodeActor.props(4096))
    val headerEncodeActor= actorSystem.actorOf(HeaderEncodeActor.props(4096))
    val streamManagerActor = actorSystem.actorOf(StreamManagerActor.props(
      new StreamManager(false, maxIncomingStreams, maxOutgoingStreams)))
    val log = Logging(actorSystem, this.getClass)

    val initialSettings = Settings(List(
      SETTINGS_ENABLE_PUSH            -> 1,
      SETTINGS_HEADER_TABLE_SIZE      -> 4096,
      SETTINGS_INITIAL_WINDOW_SIZE    -> 65536,
      SETTINGS_MAX_CONCURRENT_STREAMS -> maxIncomingStreams
    ))

    implicit val ec: ExecutionContext = actorSystem.dispatcher
    val completionPromise = Promise[Unit]()

    Flow.fromGraph(FlowGraph.create() { implicit b =>
      import FlowGraph.Implicits._
      import HeaderTransformActorBase.{Headers => HeadersMsg, Failure => HeadersFailure, _}
      import StreamManagerActor.{Failure => StreamManagerFailure, _}
      import Http2Stream._
      implicit val timeout = Timeout(30.seconds)

      val unwrapTls = b.add(Flow[SslTlsInbound].collect { case x: SessionBytes ⇒ x.bytes }.named("UnwrapTls"))
      val wrapTls = b.add(Flow[ByteString].map[SslTlsOutbound](SendBytes).named("wrapTls"))

      val frameDecoder = b.add(Flow[ByteString].transform { () =>
        new FrameDecoderStage(waitForClientPreface = true)
      }.log("frame", identity))

      val frameEncoder = b.add(Flow[Frame].transform { () =>
        new FrameEncoderStage
      }/*.log("frameEncoder")*/)

      val headerDecode =
        Flow[Frame].collect {
          case h: Headers =>
            Fragment(h.headerFragment)
          case pp: PushPromise =>
            Fragment(pp.headerFragment)
          case Settings(Setting.ExtractHeaderTableSize(tableSize), false) =>
            SetTableMaxSize(tableSize)
        }.mapAsync(maxIncomingStreams) { msg =>
          (headerDecodeActor ? msg).flatMap {
            case HeadersMsg(headers) => Future.successful(headers)
            case HeadersFailure(error)      => Future.failed(error)
            case _ => Future.failed(new RuntimeException("Unexpected header decode result"))
          }
        }

      val headerEncode =
        Flow[Seq[Header]].map { headers =>
          HeaderTransformActorBase.Headers(headers)
        }.mapAsync(maxOutgoingStreams) { msg =>
          (headerEncodeActor ? msg).flatMap {
            case Fragment(frag) => Future.successful(frag)
            case HeadersFailure(error) => Future.failed(error)
            case _ => Future.failed(new RuntimeException("Unexpected header encode result"))
          }
        } // .alsoTo(Sink.onComplete { _ => headerEncodeActor ! PoisonPill })

      val frameBypass = b.add(Broadcast[Frame](4).named("frameBypass"))

      val pingResponder = b.add(
        Flow[Frame].collect { case p: Ping => p.withFlags(Flags.PING.ACK) }.log("pingResponder")
      )
      val settingResponder = b.add(
        Flow[Frame].collect { case s: Settings if !s.ack => Settings.ack }.log("settingsResponder")
      )

      val incomingStreamDispatcher = b.add(
        new InboundStreamDispatcher(maxIncomingStreams, incomingStreamBufferSize, streamManagerActor)
      )

      val requestBuilder = mkRequestBuilder(akkaAdapter, headerDecode)/*.log("requestBuilder")*/

      val processingStartMarker = Flow[(HttpRequest, Int)].map { t =>
        streamManagerActor ! StreamProcessingStarted(t._2)
        t
      }

      val handlerGen = Flow[(HttpRequest, Int)].via(processingStartMarker).flatMapConcat { case (req, stream) =>
        Source.single(req).via(handler).recover { case e =>
          log.error(e, "Request handler failed, recovering with status 500")

          Http2Response.Simple(
            HttpResponse(StatusCodes.InternalServerError,
              entity = HttpEntity.Strict(ContentTypes.`text/plain`, ByteString("Internal server error"))
            )
          )
        }.map(resp => (resp, stream))
      }

      val responseAssigner = Flow[(Http2Response, Int)].flatMapConcat { case (r, stream) =>
        def makeMessage(msg: HttpMessage): Http2Message = {
          val headers = akkaAdapter.headersFromAkkaMessage(msg).orThrow
          val trailers = msg.headers.collect { case t: Trailer => t }.flatMap(_.fields)
          Http2Message(stream, stream, msg.entity, headers, None, trailers)
        }

        def makePromiseMessage(msg: HttpMessage, promise: HttpRequest): Future[Http2Message] = {
          val responseMsg = makeMessage(msg)
          val promiseMsg = makeMessage(promise)

          for {
            promisedStream <- (streamManagerActor ? ReserveStream).flatMap {
              case StreamManagerFailure(error) =>
                Future.failed(error)
              case Reserved(promisedStream) =>
                Future.successful(promisedStream)
              case _ =>
                Future.failed(new RuntimeException("Unexpected response from stream manager"))
            }
          } yield {
            responseMsg.copy(promise = Some(promiseMsg.copy(dataStream = promisedStream)))
          }
        }

        r match {
          case Http2Response.Promised(response, promises) =>
            val promiseMsgs = promises.mapAsync(1) { case (req, resp) => makePromiseMessage(resp, req) }
            // IMPORTANT: Push promise messages must be sent first, otherwise the response will close the stream before
            // they can be processed!
            promiseMsgs.concat(Source.single(makeMessage(response)))
          case other =>
            Source.single(makeMessage(other.response))
        }
      }

      val responseFramer = b.add(
        Flow[Http2Message].mapConcat { message =>
          def frameSingleMessage(msg: Http2Message) = {
            val firstFrame = Source.single(msg.headers).via(headerEncode).map { headerFragment =>
              if(msg.dataStream != msg.responseStream)
                PushPromise(msg.responseStream, msg.dataStream, headerFragment)
              else
                Headers(msg.responseStream, None, headerFragment)
            }

            val lastDataFrame = Data(message.dataStream, ByteString.empty, endStream = true)
            val dataFrames = message.body match {
              case ch: HttpEntity.Chunked if message.trailers.nonEmpty =>
                ch.chunks.flatMapConcat {
                  case HttpEntity.Chunk(data, _) =>
                    Source.single(Data(message.dataStream, data, endStream = false))
                  case HttpEntity.LastChunk(_, trailer) =>
                    val headers = akkaAdapter.headersFromAkka(trailer)
                    if(headers.map(_.name) != message.trailers)
                      throw InternalError("Produced trailer headers don't match ones specified at message start")

                    Source.single(headers).via(headerEncode).map { frag =>
                      Headers(message.dataStream, None, frag, endStream = true)
                    }
                }
              case ch: HttpEntity.Chunked =>
                ch.chunks.collect {
                  case HttpEntity.Chunk(data, _) =>
                    Data(message.dataStream, data, endStream = false)
                }.concat(Source.single(lastDataFrame))
              case body =>
                body.dataBytes.map { bs =>
                  Data(message.dataStream, bs, endStream = false)
                }.concat(Source.single(lastDataFrame))
            }

            firstFrame.concat(dataFrames): Source[Frame, Unit]
          }

          message.promise match {
            case Some(promiseRequest) => immutable.Seq(frameSingleMessage(promiseRequest), frameSingleMessage(message))
            case None                 => immutable.Seq(frameSingleMessage(message))
          }
        }
      )

      val responseMerger = b.add(Merge[Http2Message](maxIncomingStreams, eagerClose = false)
        .named("responseMerger"))
      val responseBalance = b.add(Balance[Source[Frame, Any]](maxOutgoingStreams))

      val outgoingStreamHandler = Flow[Source[Frame, Any]].flatMapConcat { s =>
        s.mapAsync(8) { frame =>
          (streamManagerActor ? OutgoingFrame(frame)).flatMap {
            case StreamManagerFailure(error) =>
              Future.failed(error)
            case OutgoingAction(_, Stop) =>
              Future.failed(StreamClosedError())
            case OutgoingAction(_, Continue(f)) =>
              Future.successful((f, false))
            case OutgoingAction(_, Finish(f)) =>
              Future.successful((f, true))
            case _ =>
              Future.failed(new RuntimeException("Unexpected response from stream manager"))
          }
        }.transform(() => new PushStage[(Frame, Boolean), Frame] {
          override def onPush(elem: (Frame, Boolean), ctx: Context[Frame]): SyncDirective = {
            if(elem._2) ctx.pushAndFinish(elem._1)
            else        ctx.push(elem._1)
          }
        })
      }

      val outgoingFrameMerge = b.add(Merge[Frame](maxOutgoingStreams + 3))
      val outgoingFrameBypass = b.add(Flow[Frame])

      val incomingTerminationWatcher = b.add(
        Flow[Frame].filter(_.isInstanceOf[GoAway]).to(Sink.foreach { _ =>
          throw NoError("Finishing due to peer request")
        })
      )

      val goAwayStreamAssigner = b.add(
        Flow[Frame].map {
          case g: GoAway =>
            val lastStreamFuture = (streamManagerActor ? GetLastProcessedStream).collect {
              case LastProcessedStream(streamId) => streamId
            }

            // We just wait synchronously here because it will certainly be the last thing we do, so it doesn't cause
            // any trouble
            val lastStream = try {
              Await.result(lastStreamFuture, 5.second)
            } catch { case e: Exception =>
              Int.MaxValue
            }

            g.copy(lastStream = lastStream)
          case f => f
        }
      )

      frameBypass <~ frameDecoder <~ unwrapTls
      frameBypass ~> incomingStreamDispatcher
      frameBypass ~> pingResponder
      frameBypass ~> settingResponder
      frameBypass ~> incomingTerminationWatcher

      for(inStreamPort <- 0 until maxIncomingStreams) {

        val streamResponseAssigner = b.add(responseAssigner.named("responseAssigner"))

        incomingStreamDispatcher ~> requestBuilder ~> handlerGen ~> streamResponseAssigner ~> responseMerger
      }

      responseMerger ~> responseFramer ~> responseBalance

      for(outStreamPort <- 0 until maxOutgoingStreams) {
        responseBalance ~> outgoingStreamHandler ~> outgoingFrameMerge
      }

                             outgoingFrameMerge <~ Source.single(initialSettings)
                             outgoingFrameMerge <~ pingResponder
                             outgoingFrameMerge <~ settingResponder
      outgoingFrameBypass <~ outgoingFrameMerge.out
      outgoingFrameBypass ~> goAwayStreamAssigner ~> frameEncoder ~> wrapTls

      FlowShape(unwrapTls.inlet, wrapTls.outlet)
    }).mapMaterializedValue[Future[Unit]](_ => completionPromise.future)
  }
}

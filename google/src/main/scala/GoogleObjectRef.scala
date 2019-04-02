/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source, StreamConverters }
import akka.util.ByteString

import play.api.libs.json.{ JsObject, JsString, Json }

import play.api.libs.ws.{ BodyWritable, StandaloneWSResponse }
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.JsonBodyWritables._

import com.google.api.client.http.ByteArrayContent
import com.google.api.services.storage.model.StorageObject

import com.zengularity.benji.exception.{
  BenjiUnknownError,
  ObjectNotFoundException
}

import com.zengularity.benji.{
  ByteRange,
  Bytes,
  Chunk,
  ObjectRef,
  Streams,
  ObjectVersioning,
  VersionedObjectRef,
  VersionedObject
}
import com.zengularity.benji.ws.{ ContentMD5, Ok, Successful }
import scala.collection.JavaConverters._

final class GoogleObjectRef private[google] (
  storage: GoogleStorage,
  val bucket: String,
  val name: String) extends ObjectRef with ObjectVersioning { ref =>
  import GoogleObjectRef.ResumeIncomplete

  @inline def defaultThreshold = GoogleObjectRef.defaultThreshold

  @inline private def logger = storage.logger
  @inline private def gt = storage.transport

  def exists(implicit ec: ExecutionContext): Future[Boolean] = Future {
    gt.client.objects().get(bucket, name).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }

  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = Future {
    val resp = gt.client.objects().get(bucket, name).executeUnparsed()

    resp.parseAsString
  }.flatMap {
    Json.parse(_) match {
      case JsObject(fields) => Future(fields.toMap.flatMap {
        case (key, JsString(value)) => Seq(key -> Seq(value))

        case ("metadata", JsObject(metadata)) => metadata.collect {
          case (key, JsString(value)) => s"metadata.$key" -> Seq(value)
        }

        case _ => Seq.empty[(String, Seq[String])] // unsupported
      })

      case js => Future.failed[Map[String, Seq[String]]](new IllegalStateException(s"Could not get the headers of the object $name in the bucket $bucket. JSON response: ${Json stringify js}"))
    }
  }.recoverWith(ErrorHandler.ofObjectToFuture(s"Could not get the headers of the object $name in the bucket $bucket", ref))

  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = headers().map { headers =>
    headers.collect { case (key, value) if key.startsWith("metadata") => key.stripPrefix("metadata.") -> value }
  }

  val get = new GoogleGetRequest()

  def put[E, A] = new RESTPutRequest[E, A]()

  def delete: DeleteRequest = GoogleDeleteRequest()

  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext): Future[Unit] = {
    val targetObj = storage.bucket(targetBucketName).obj(targetObjectName)

    for {
      _ <- {
        if (!preventOverwrite) Future.successful({})
        else targetObj.exists.flatMap {
          case true => Future.failed[Unit](new IllegalStateException(
            s"Could not move $bucket/$name: target $targetBucketName/$targetObjectName already exists"))

          case _ => Future.successful({})
        }
      }
      _ <- Future {
        @SuppressWarnings(Array("org.wartremover.warts.Null"))
        @inline def unsafe = gt.client.objects().copy(bucket, name,
          targetBucketName, targetObjectName, null).execute()

        unsafe
      }.map(_ => {}).recoverWith {
        case reason => targetObj.delete.ignoreIfNotExists().filter(_ => false).
          recoverWith {
            case _ => Future.failed[Unit](reason)
          }
      }
      _ <- delete() /* the previous reference */
    } yield ()
  }

  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext): Future[Unit] = Future {
    gt.client.objects().
      copy(bucket, name, targetBucketName, targetObjectName, null).execute()
  }.map(_ => {})

  // ---

  /** A GET request for Google Cloud Storage */
  final class GoogleGetRequest private[google] () extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFutureSource(Future {
        val req = gt.client.objects().get(bucket, name)

        req.setRequestHeaders {
          val headers = new com.google.api.client.http.HttpHeaders()

          range.foreach { r =>
            headers.setRange(s"bytes=${r.start}-${r.end}")
          }

          headers
        }

        req.setDisableGZipContent(storage.disableGZip)

        val in = req.executeMediaAsInputStream() // using alt=media
        StreamConverters.fromInputStream(() => in)
      }.recoverWith(ErrorHandler.ofObjectToFuture(s"Could not get the contents of the object $name in the bucket $bucket", ref)))
    }.mapMaterializedValue(_ => NotUsed)
  }

  /**
   * A PUT request directly using REST API of Google Cloud Storage.
   *
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload
   */
  final class RESTPutRequest[E, A] private[google] ()
    extends ref.PutRequest[E, A] {

    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None, metadata: Map[String, String] = Map.empty[String, String])(f: (A, Chunk) => Future[A])(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[A]] = {
      def flowChunks = Streams.chunker[E].via(Streams.consumeAtMost(threshold))

      flowChunks.prefixAndTail(1).flatMapMerge[A, NotUsed](1, {
        case (Nil, _) => Source.empty[A]

        case (head, tail) => head.toList match {
          case (last @ Chunk.Last(_)) :: _ => // if first is last, single chunk
            Source.single(last).via(putSimple(Option(w.contentType), metadata, z, f))

          case first :: _ => {
            def source = tail.zip(initiateUpload(Option(w.contentType), metadata).
              flatMapConcat(Source.repeat /* same ID for all */ ))

            source.via(putMulti(first, Option(w.contentType), z, f))
          }
        }
      }).toMat(Sink.head[A]) { (_, mat) => mat }
    }
  }

  private case class GoogleDeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      val futureResult = Future {
        gt.client.objects().delete(bucket, name).execute(); ()
      }.recoverWith(ErrorHandler.ofObjectToFuture(s"Could not delete object $name inside bucket $bucket", ref))

      if (ignoreExists) {
        futureResult.recover { case ObjectNotFoundException(_, _) => () }
      } else {
        futureResult
      }
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  // Utility methods

  /**
   * Creates an Flow that will upload the bytes it consumes in one request,
   * without streaming them.
   *
   * For this operation we need to know the overall content length
   * (the server requires that), which is why we have to buffer
   * everything upfront.
   *
   * If you already know that your upload will exceed the threshold,
   * then use multi-part uploads.
   *
   * @param contentType $contentTypeParam
   *
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#simple
   */
  private def putSimple[A](contentType: Option[String], metadata: Map[String, String], z: => A, f: (A, Chunk) => Future[A])(implicit m: Materializer): Flow[Chunk, A, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Flow[Chunk].limit(1).flatMapConcat { single =>
      lazy val typ = contentType getOrElse "application/octet-stream"
      def content = new ByteArrayContent(typ, single.data.toArray)
      def obj = {
        val so = new StorageObject()

        so.setBucket(bucket)
        so.setName(name)
        so.setContentType(typ)
        so.setSize(new java.math.BigInteger(single.size.toString))
        so.setMetadata(metadata.asJava)

        so
      }

      Source.fromFuture(Future {
        val req = gt.client.objects().insert(bucket, obj, content)

        req.setDisableGZipContent(storage.disableGZip)

        req.execute()
      }.flatMap { _ => f(z, single) }.recoverWith {
        case t => ErrorHandler.ofBucketToFuture(
          s"Could not upload $name in $bucket", bucket)(t)
      })
    }
  }

  /**
   * Creates an Flow that will upload the bytes.
   * It consumes in multi-part uploads.
   *
   * @param firstChunk the data of the first chunk (already consumed)
   * @param contentType $contentTypeParam
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#multipart
   */
  private def putMulti[A](firstChunk: Chunk, contentType: Option[String], z: => A, f: (A, Chunk) => Future[A])(implicit m: Materializer): Flow[(Chunk, String), A, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    @inline def zst = (Option.empty[String], 0L, firstChunk, z)

    Flow.apply[(Chunk, String)].
      foldAsync[(Option[String], Long, Chunk, A)](zst) {
        case ((_, offset, prev, st), (chunk, url)) =>
          uploadPart(url, prev.data, offset, contentType).flatMap { _ =>
            chunk match {
              case last @ Chunk.Last(data) => f(st, prev).flatMap { tmp =>
                val off = offset + prev.size
                val sz = off + data.size.toLong

                for {
                  _ <- uploadPart(url, data, off, contentType, Some(sz))
                  nst <- f(tmp, last)
                } yield (Some(url), sz, Chunk.last(ByteString.empty), nst)
              }

              case ne @ Chunk.NonEmpty(_) => f(st, prev).map { nst =>
                (Some(url), (offset + prev.size), ne, nst)
              }

              case _ => f(st, prev).map {
                (Some(url), offset, Chunk.last(ByteString.empty), _)
              }
            }
          }
      }.mapAsync[A](1) {
        case (Some(_), _, _, res) => Future.successful(res)

        case st =>
          Future.failed[A](
            new IllegalStateException(s"invalid upload state: $st"))
      }
  }

  /**
   * Initiates a multi-part upload and returns the upload URL
   * (include the upload ID).
   *
   * @param contentType $contentTypeParam
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#start-resumable
   */
  private def initiateUpload(contentType: Option[String], metadata: Map[String, String])(implicit m: Materializer): Source[String, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(gt
      .withWSRequest1(
        "/upload", s"/b/${UriUtils.encodePathSegment(bucket, "UTF-8")}/o") { req =>

          val init = contentType.fold(req) { typ =>
            req.addHttpHeaders(
              "Content-Type" -> "application/json",
              "X-Upload-Content-Type" -> typ)
          }.withQueryStringParameters("uploadType" -> "resumable", "name" -> name).
            withMethod("POST").withBody(Json.obj(
              "name" -> name,
              "contentType" -> contentType,
              "metadata" -> metadata))

          // TODO: cURL Debug?

          init.execute().flatMap {
            case Successful(response) => response.header("Location") match {
              case Some(url) => Future.successful {
                logger.debug(s"Initiated a resumable upload for $bucket/$name: $url")
                url
              }
              case _ =>
                Future.failed[String](new BenjiUnknownError(s"missing upload URL: ${response.status} - ${response.statusText}: ${response.headers}"))
            }
            case response =>
              ErrorHandler.ofBucketFromResponse(s"Could not initiate upload for $name in bucket $bucket", bucket)(response)
          }
        })
  }

  /**
   * Uploads a part in a resumable upload.
   * @define offsetParam the offset of the bytes in the global content
   *
   * @param url the URL previously initiated for the resumable uplaod
   * @param bytes the bytes for the part content
   * @param offset $offsetParam
   * @param contentType $contentTypeParam
   * @param globalSz the global size (if known)
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#uploading_the_file_in_chunks
   */
  private def uploadPart(url: String, bytes: ByteString, offset: Long, contentType: Option[String], globalSz: Option[Long] = None)(implicit m: Materializer): Future[String] = {
    implicit def ec: ExecutionContext = m.executionContext

    gt.withWSRequest2(url) { req =>
      val limit = globalSz.fold("*")(_.toString)
      val reqRange =
        s"bytes $offset-${offset + bytes.size - 1}/${limit}"

      val baseReq = req.addHttpHeaders(
        "Content-Length" -> bytes.size.toString,
        "Content-Range" -> reqRange)

      logger.debug(s"Prepare upload part: $reqRange; size = ${bytes.size}")

      val uploadReq = contentType.fold(baseReq) { typ =>
        baseReq.addHttpHeaders("Content-Type" -> typ)
      }.addHttpHeaders("Content-MD5" -> ContentMD5(bytes)).
        withMethod("PUT").withBody(bytes)

      uploadReq.execute().flatMap {
        case Ok(_) =>
          Future.successful(reqRange)

        case ResumeIncomplete(resumeResponse) =>
          partResponse(resumeResponse, offset, bytes.size, url)

        case Successful(response) =>
          partResponse(response, offset, bytes.size, url)

        case response =>
          ErrorHandler.ofBucketFromResponse(s"Could not upload a part for [$bucket/$name, $url, range: $reqRange]", bucket)(response)
      }
    }
  }

  /**
   * @param response the WS response from a part upload
   * @param offset the offset of the bytes in the global content
   * @param sz the current upload size
   * @param url the request URL of the given response
   * @return the uploaded range if successful
   */
  @inline private def partResponse(response: StandaloneWSResponse, offset: Long, sz: Int, url: String): Future[String] = response.header("Range") match {
    case Some(range) => Future.successful {
      logger.trace(s"Uploaded part @$offset with $sz bytes: $url")
      range
    }

    case _ => Future.failed[String](BenjiUnknownError(s"missing upload range: ${response.status} - ${response.statusText}: ${response.headers}"))
  }

  def versioning: Option[ObjectVersioning] = Some(this)

  override lazy val toString = s"GoogleObjectRef($bucket, $name)"

  override def equals(that: Any): Boolean = that match {
    case other: GoogleObjectRef =>
      other.tupled == this.tupled

    case _ => false
  }

  override def hashCode: Int = tupled.hashCode

  @inline private def tupled = bucket -> name

  // ---

  private case class ObjectsVersions(maybeMax: Option[Long]) extends ref.VersionedListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    @SuppressWarnings(Array("org.wartremover.warts.Recursion", "org.wartremover.warts.Throw"))
    private def apply(nextToken: Option[String], maybeEmpty: Boolean)(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      implicit val ec: ExecutionContext = m.executionContext

      Source.fromFutureSource(Future {
        val prepared = gt.client.objects().list(bucket).setVersions(true).setPrefix(name)
        val maxed = maybeMax.fold(prepared) { prepared.setMaxResults(_) }

        val request =
          nextToken.fold(maxed.execute()) { maxed.setPageToken(_).execute() }

        val (currentPage, empty) = Option(request.getItems) match {
          case Some(items) =>
            val collection = items.asScala.filter(_.getName == name)
            val source = Source.fromIterator[VersionedObject] { () =>
              collection.iterator.map { obj: StorageObject =>
                VersionedObject(
                  obj.getName,
                  Bytes(obj.getSize.longValue),
                  LocalDateTime.ofInstant(Instant.ofEpochMilli(obj.getUpdated.getValue), ZoneOffset.UTC),
                  obj.getGeneration.toString,
                  obj.getTimeDeleted == null)
              }
            }
            (source, collection.isEmpty)

          case _ => (Source.empty[VersionedObject], true)
        }

        Option(request.getNextPageToken) match {
          case nextPageToken @ Some(_) =>
            currentPage ++ apply(nextPageToken, maybeEmpty = maybeEmpty && empty)

          case _ =>
            if (maybeEmpty && empty)
              throw ObjectNotFoundException(ref)
            else
              currentPage
        }
      }.recoverWith(ErrorHandler.ofObjectToFuture(s"Could not list versions of object $name inside bucket $bucket", ref)))
    }.mapMaterializedValue(_ => NotUsed)

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = apply(None, maybeEmpty = true)
  }

  def versions: VersionedListRequest = ObjectsVersions(None)

  def version(versionId: String): VersionedObjectRef =
    new GoogleVersionedObjectRef(storage, bucket, name, versionId)
}

object GoogleObjectRef {
  /**
   * The default threshold for multi-part upload.
   *
   * @see https://cloud.google.com/storage/docs/json_api/v1/how-tos/upload#uploading_the_file_in_chunks
   */
  val defaultThreshold: Bytes = Bytes.kilobytes(256)

  private[google] object ResumeIncomplete {
    def unapply(response: StandaloneWSResponse): Option[StandaloneWSResponse] =
      if (response.status == 308) Some(response) else None
  }
}

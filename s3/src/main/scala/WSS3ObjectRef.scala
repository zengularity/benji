/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import scala.collection.immutable.Iterable

import scala.concurrent.{ ExecutionContext, Future }
import scala.xml.Elem

import akka.NotUsed
import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }

import play.api.libs.ws.{
  BodyWritable,
  StandaloneWSRequest,
  StandaloneWSResponse
}
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.XMLBodyWritables._

import com.github.ghik.silencer.silent
import com.zengularity.benji.{
  ByteRange,
  Bytes,
  Chunk,
  Compat,
  ObjectRef,
  ObjectVersioning,
  Streams,
  VersionedObject,
  VersionedObjectRef
}
import com.zengularity.benji.exception.ObjectNotFoundException
import com.zengularity.benji.s3.QueryParameters._
import com.zengularity.benji.ws.{ ContentMD5, Successful }

final class WSS3ObjectRef private[s3] (
    private val storage: WSS3,
    val bucket: String,
    val name: String)
    extends ObjectRef
    with ObjectVersioning { ref =>

  /** The maximum number of part (10,000) for a multipart upload to S3/AWS. */
  val defaultMaxPart: Int = 10000

  /** The default threshold for multi-part upload. */
  val defaultThreshold: Bytes = Bytes.megabytes(5)

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout
  // @inline private implicit def ws = storage.transport

  /**
   * @see [[http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html RESTObjectHEAD]]
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage
      .request(Some(bucket), Some(name), requestTimeout = requestTimeout)
      .head()
      .map(_.status == 200)

  def get = new RESTGetRequest(this)

  def headers(
    )(implicit
      ec: ExecutionContext
    ): Future[Map[String, Seq[String]]] = {
    def req =
      storage.request(Some(bucket), Some(name), requestTimeout = requestTimeout)

    req.head().flatMap {
      case response if response.status == 200 =>
        Future(Compat.mapValues(response.headers)(_.toSeq))

      case response =>
        val error = ErrorHandler.ofObject(
          s"Could not get the head of the object $name in the bucket $bucket",
          ref
        )(response)
        Future.failed[Map[String, Seq[String]]](error)
    }
  }

  def metadata(
    )(implicit
      ec: ExecutionContext
    ): Future[Map[String, Seq[String]]] = headers().map { headers =>
    headers.collect {
      case (key, value) if key.startsWith("x-amz-meta-") =>
        key.stripPrefix("x-amz-meta-") -> value
    }
  }

  override def put[E, A]: RESTPutRequest[E, A] =
    new RESTPutRequest[E, A](defaultMaxPart)

  def delete: DeleteRequest = WSS3DeleteRequest()

  /**
   * @see #copyTo
   * @see #delete
   */
  def moveTo(
      targetBucketName: String,
      targetObjectName: String,
      preventOverwrite: Boolean
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] = {
    val targetObj = storage.bucket(targetBucketName).obj(targetObjectName)

    for {
      _ <- {
        if (!preventOverwrite) Future.successful({})
        else
          targetObj.exists.flatMap {
            case true =>
              Future.failed[Unit](
                new IllegalStateException(
                  s"Could not move $bucket/$name: target $targetBucketName/$targetObjectName already exists"
                )
              )

            case _ => Future.successful({})
          }
      }
      _ <- copyTo(targetBucketName, targetObjectName).recoverWith {
        case reason =>
          targetObj.delete.ignoreIfNotExists().filter(_ => false).recoverWith {
            case _ => Future.failed[Unit](reason)
          }
      }
      _ <- delete() /* the previous reference */
    } yield ()
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectCOPY.html
   * !! There are known issue with CEPH
   */
  def copyTo(
      targetBucketName: String,
      targetObjectName: String
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] =
    storage
      .request(
        Some(targetBucketName),
        Some(targetObjectName),
        requestTimeout = requestTimeout
      )
      .addHttpHeaders(
        "x-amz-copy-source" -> java.net.URLEncoder
          .encode(s"/$bucket/$name", "UTF-8")
      )
      .put("")
      .flatMap {
        case Successful(_) =>
          Future.successful(
            logger.info(s"Successfully copied the object [$bucket/$name] to [$targetBucketName/$targetObjectName].")
          )

        case response =>
          Future.failed[Unit](
            new IllegalStateException(
              s"Could not copy the object [$bucket/$name] to [$targetBucketName/$targetObjectName]. Response: ${response.status.toString} - ${response.statusText}; ${response.body}"
            )
          )
      }

  def versioning: Option[ObjectVersioning] = Some(this)

  def versions: VersionedListRequest = ObjectVersions(None)

  def version(versionId: String): VersionedObjectRef =
    new WSS3VersionedObjectRef(storage, bucket, name, versionId)

  override lazy val toString = s"WSS3ObjectRef($bucket, $name)"

  override def equals(that: Any): Boolean = that match {
    case other: WSS3ObjectRef =>
      other.tupled == this.tupled

    case _ => false
  }

  override def hashCode: Int = tupled.hashCode

  @inline private def tupled = bucket -> name

  // Utility methods

  /**
   * A S3 GET request.
   *
   * @see [[http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html RESTObjectGET]]
   */
  final class RESTGetRequest(val target: ref.type) extends GetRequest {

    @silent(".*fromFutureSource.*")
    def apply(
        range: Option[ByteRange] = None
      )(implicit
        m: Materializer
      ): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      def req = storage.request(
        Some(bucket),
        Some(name),
        requestTimeout = requestTimeout
      )

      Source
        .fromFutureSource[ByteString, NotUsed](
          range
            .fold(req)(r =>
              req.addHttpHeaders(
                "Range" -> s"bytes=${r.start.toString}-${r.end.toString}"
              )
            )
            .withMethod("GET")
            .stream()
            .flatMap {
              case response
                  if response.status == 200 || response.status == 206 =>
                Future.successful(
                  response.bodyAsSource.mapMaterializedValue(_ => NotUsed)
                )
              case response =>
                val err = ErrorHandler.ofObject(
                  s"Could not get the contents of the object $name in the bucket $bucket",
                  ref
                )(response)
                Future.failed[Source[ByteString, NotUsed]](err)
            }
        )
        .mapMaterializedValue(_ => NotUsed)
    }
  }

  /**
   * A S3 PUT request.
   * @see [[http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html RESTObjectPUT]] and [[http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html Multipart Upload Overview]]
   *
   * @define maxPartParam the maximum number of part
   * @param maxPart $maxPartParam
   */
  final class RESTPutRequest[E, A] private[s3] (val maxPart: Int)
      extends ref.PutRequest[E, A] {

    /**
     * Returns an updated request with the given maximum number of part.
     *
     * @param max $maxPartParam
     */
    def withMaxPart(max: Int): RESTPutRequest[E, A] =
      new RESTPutRequest[E, A](max)

    /**
     * If the `size` is known and if the partitioning according that,
     * and the `threshold` would exceed the `maxPart`, then `size / maxPart`
     * is used instead of the given threshold for multipart upload.
     *
     * @param metadata (without the `x-amz-meta-` prefix for the keys)
     */
    def apply(
        z: => A,
        threshold: Bytes = defaultThreshold,
        size: Option[Long] = None,
        metadata: Map[String, String] = Map.empty[String, String]
      )(f: (A, Chunk) => Future[A]
      )(implicit
        m: Materializer,
        w: BodyWritable[E]
      ): Sink[E, Future[A]] = {
      val th = size.filter(_ > 0).fold(threshold) { sz =>
        val partCount = sz /: threshold
        if (partCount <= maxPart) threshold else Bytes(sz / maxPart)
      }
      implicit def ec: ExecutionContext = m.executionContext

      def flowChunks = Streams.chunker[E].via(Streams.consumeAtLeast(th))

      val amzHeaders = metadata.map {
        case (key, value) => s"x-amz-meta-${key}" -> value
      }

      flowChunks
        .prefixAndTail(1)
        .flatMapMerge[A, NotUsed](
          1,
          {
            case (head, tail) =>
              head.toList match {
                case (last @ Chunk
                      .Last(_)) :: _ => // if first is last, single chunk
                  Source
                    .single(last)
                    .via(putSimple(Option(w.contentType), amzHeaders, z, f))

                case first :: _ => {
                  def chunks =
                    Source.single(first) ++ tail // push back the first
                  def source = chunks.zip(
                    initiateUpload(amzHeaders)
                      .flatMapConcat(Source.repeat /* same ID for all */ )
                  )

                  source.via(putMulti(Option(w.contentType), z, f))
                }

                case _ => Source.empty[A]
              }
          }
        )
        .toMat(Sink.head[A]) { (_, mat) => mat }
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
   */
  private case class WSS3DeleteRequest(ignoreExists: Boolean = false)
      extends DeleteRequest {

    private def delete(implicit ec: ExecutionContext): Future[Unit] = {
      storage
        .request(Some(bucket), Some(name), requestTimeout = requestTimeout)
        .delete()
        .flatMap {
          case Successful(_) =>
            logger.info(s"Successfully deleted the object $bucket/$name.")
            Future.successful({}) // .unit > 2.12

          case response =>
            ErrorHandler.ofObject(
              s"Could not delete object $name inside $bucket",
              ref
            )(response) match {
              case ObjectNotFoundException(_, _) if ignoreExists =>
                Future.successful({}) // .unit > 2.12

              case throwable =>
                Future.failed[Unit](throwable)
            }
        }
    }

    private def checkExists(implicit ec: ExecutionContext) =
      if (ignoreExists) {
        Future.successful({}) // .unit > 2.12
      } else {
        exists.flatMap {
          case false => Future.failed[Unit](ObjectNotFoundException(ref))
          case true  => Future.successful({}) // .unit > 2.12
        }
      }

    def apply()(implicit ec: ExecutionContext): Future[Unit] =
      checkExists.flatMap(_ => delete)

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

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
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html
   */
  @silent(".*fromFuture.*")
  private def putSimple[A](
      contentType: Option[String],
      metadata: Map[String, String],
      z: => A,
      f: (A, Chunk) => Future[A]
    )(implicit
      m: Materializer
    ): Flow[Chunk, A, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Flow[Chunk].limit(1).flatMapConcat { single =>
      val req = storage
        .request(Some(bucket), Some(name), requestTimeout = requestTimeout)
        .addHttpHeaders(
          (("Content-MD5" -> ContentMD5(single.data)) +: (metadata.toSeq)): _*
        )

      Source.fromFuture(
        withContentTypeHeader(req, contentType)
          .put(single.data)
          .flatMap {
            case Successful(_) =>
              Future.successful(
                logger.debug(s"Completed the simple upload for $bucket/$name.")
              )

            case response =>
              val handler = ErrorHandler.ofBucket(
                s"Could not update the contents of the object $name in $bucket",
                bucket
              )(_)
              Future.failed[Unit](handler(response))

          }
          .flatMap(_ => f(z, single))
      )
    }
  }

  /**
   * Creates an Flow that will upload the bytes.
   * It consumes of source of chunks (each tagged with the upload ID).
   *
   * @param contentType $contentTypeParam
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
   */
  private def putMulti[A](
      contentType: Option[String],
      z: => A,
      f: (A, Chunk) => Future[A]
    )(implicit
      m: Materializer
    ): Flow[(Chunk, String), A, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    @inline def zst = (Option.empty[String], List.empty[String], z)

    Flow
      .apply[(Chunk, String)]
      .foldAsync[(Option[String], List[String], A)](zst) {
        case ((_, etags, st), (chunk, id)) =>
          (for {
            etag <- uploadPart(chunk.data, contentType, etags.size + 1, id)
            nst <- f(st, chunk)
          } yield (Some(id), (etag :: etags), nst))
      }
      .mapAsync[A](1) {
        case (Some(id), etags, res) =>
          completeUpload(etags.reverse, id).map(_ => res)

        case st =>
          Future.failed[A](
            new IllegalStateException(s"invalid upload state: ${st.toString}")
          )
      }
  }

  /**
   * Initiates a multi-part upload and returns the upload ID we're supposed to include when uploading parts later on.
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadInitiate.html
   */
  @silent(".*fromFuture.*")
  private def initiateUpload(
      metadata: Map[String, String]
    )(implicit
      ec: ExecutionContext
    ): Source[String, NotUsed] = Source fromFuture {
    storage
      .request(
        Some(bucket),
        Some(name),
        Some("uploads"),
        requestTimeout = requestTimeout
      )
      .addHttpHeaders(metadata.toSeq: _*)
      .post("")
      .flatMap {
        case Successful(response) => {
          val xmlResponse = scala.xml.XML.loadString(response.body)
          val uploadId = (xmlResponse \ "UploadId").text

          logger.debug(s"Initiated a multi-part upload for $bucket/$name using the ID $uploadId.")

          Future.successful(uploadId)
        }

        case response =>
          val handler = ErrorHandler.ofBucket(
            s"Could not initiate the upload for object $name in $bucket",
            bucket
          )(_)
          Future.failed[String](handler(response))
      }
  }

  /**
   * Uploads a part in a multi-part upload.
   * Note that each part (the bytes) needs to be bigger than 5 MB,
   * except the last one.
   * It returns the ETag header returned for that uploaded part,
   * something that we'll need to keep track of to finish the upload later on.
   *
   * @param bytes the bytes for the part content
   * @param contentType $contentTypeParam
   * @param partNumber the number of the part, within the current upload
   * @param uploadId the unique ID of the current upload
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadUploadPart.html
   */
  private def uploadPart(
      bytes: ByteString,
      contentType: Option[String],
      partNumber: Int,
      uploadId: String
    )(implicit
      ec: ExecutionContext
    ): Future[String] = {
    val req = storage
      .request(
        Some(bucket),
        Some(name),
        query = Some(s"partNumber=${partNumber.toString}&uploadId=$uploadId"),
        requestTimeout = requestTimeout
      )
      .addHttpHeaders("Content-MD5" -> ContentMD5(bytes))

    withContentTypeHeader(req, contentType).put(bytes).flatMap {
      case Successful(Etag(value)) =>
        Future.successful {
          logger.trace(s"Uploaded part ${partNumber.toString} with ${bytes.length.toString} bytes of the upload $uploadId for $bucket/$name ($value).")

          value
        }

      case response @ Successful(_) =>
        Future.failed[String](
          new IllegalStateException(
            s"Response for the upload [$bucket/$name, $uploadId, part: ${partNumber.toString}] did not include an ETag header: ${response.headers
              .mkString("{", ",", "}")}."
          )
        )

      case response =>
        val handler = ErrorHandler.ofBucket(
          s"Could not upload a part for [$bucket/$name, $uploadId, part: ${partNumber.toString}]",
          bucket
        )(_)
        Future.failed[String](handler(response))
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadComplete.html
   */
  private def completeUpload(
      etags: List[String],
      uploadId: String
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] = {
    storage
      .request(
        Some(bucket),
        Some(name),
        query = Some(s"uploadId=$uploadId"),
        requestTimeout = requestTimeout
      )
      .post(<CompleteMultipartUpload>
          {
        val result: List[Elem] = etags.zipWithIndex.map {
          case (etag, partNumber) =>
            // Part numbers start at index 1 rather than 0
            <Part><PartNumber>{
              (partNumber + 1).toString
            }</PartNumber><ETag>{etag}</ETag></Part>
        }

        result
      }
        </CompleteMultipartUpload>)
      .flatMap {
        case Successful(_) =>
          Future.successful(
            logger.debug(s"Completed the upload $uploadId for $bucket/$name.")
          )

        case response =>
          val handler = ErrorHandler.ofBucket(
            s"Could not complete the upload for [$bucket/$name, $uploadId]",
            bucket
          )(_)
          Future.failed[Unit](handler(response))
      }
  }

  @inline private def withContentTypeHeader(
      req: StandaloneWSRequest,
      contentType: Option[String]
    ): StandaloneWSRequest =
    contentType.fold(req)(c => req.addHttpHeaders("Content-Type" -> c))

  private object Etag {

    def unapply(response: StandaloneWSResponse): Option[String] =
      response.header("ETag")
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  private[s3] case class ObjectVersions(
      maybeMax: Option[Long] = None,
      includeDeleteMarkers: Boolean = false)
      extends ref.VersionedListRequest {

    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def withDeleteMarkers: ObjectVersions =
      this.copy(includeDeleteMarkers = true)

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def next(nextToken: String): Source[VersionedObject, NotUsed] =
        list(Some(nextToken))(next(_), None)

      // if on first page request we get no result, it means the object does not exists
      list(Option.empty[String])(next(_), Some(ObjectNotFoundException(ref)))
    }

    def list(
        token: Option[String]
      )(andThen: String => Source[VersionedObject, NotUsed],
        whenEmpty: Option[Throwable]
      )(implicit
        m: Materializer
      ): Source[VersionedObject, NotUsed] = {
      val parse: Elem => Iterable[VersionedObject] = { xml =>
        ({
          if (includeDeleteMarkers) {
            (xml \ "Version").map(Xml.versionDecoder) ++ (xml \ "DeleteMarker")
              .map(Xml.deleteMarkerDecoder)
          } else {
            (xml \ "Version").map(Xml.versionDecoder)
          }
        }).filter(_.name == name)
      }

      val query: Option[String] => Option[String] = { token =>
        buildQuery(
          versionParam,
          prefixParam(ref.name),
          maxParam(maybeMax),
          tokenParam(token)
        )
      }

      val errorHandler = ErrorHandler.ofObject(
        s"Could not list versions of object $name in bucket $bucket",
        ref
      )(_)

      WSS3BucketRef.list[VersionedObject](
        ref.storage,
        ref.bucket,
        token,
        errorHandler
      )(query, parse, _.name, andThen, whenEmpty)
    }
  }
}

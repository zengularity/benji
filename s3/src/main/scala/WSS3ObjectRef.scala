package com.zengularity.benji.s3

import scala.xml.Elem
import scala.collection.immutable.Iterable
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.util.ByteString

import play.api.libs.ws.{
  BodyWritable,
  StandaloneWSRequest,
  StandaloneWSResponse
}
import play.api.libs.ws.DefaultBodyWritables._
import play.api.libs.ws.XMLBodyWritables._

import com.zengularity.benji.{
  ByteRange,
  Bytes,
  Chunk,
  ObjectRef,
  Streams,
  ObjectVersioning,
  VersionedObject,
  VersionedObjectRef
}
import com.zengularity.benji.s3.QueryParameters._
import com.zengularity.benji.ws.{ ContentMD5, Successful }

final class WSS3ObjectRef private[s3] (
  val storage: WSS3,
  val bucket: String,
  val name: String) extends ObjectRef with ObjectVersioning { ref =>

  /** The maximum number of part (10,000) for a multipart upload to S3/AWS. */
  val defaultMaxPart: Int = 10000

  val defaultThreshold: Bytes = Bytes.megabytes(5)

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout
  //@inline private implicit def ws = storage.transport

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage.request(Some(bucket), Some(name), requestTimeout = requestTimeout).
      head().map(_.status == 200)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
   */
  final class RESTGetRequest(val target: ref.type) extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      def req = storage.request(
        Some(bucket), Some(name), requestTimeout = requestTimeout)

      Source.fromFutureSource[ByteString, NotUsed](range.fold(req)(r => req.addHttpHeaders(
        "Range" -> s"bytes=${r.start}-${r.end}")).withMethod("GET").stream().flatMap { response =>
        if (response.status == 200 || response.status == 206) {
          Future.successful(response.bodyAsSource.
            mapMaterializedValue(_ => NotUsed))

        } else {
          Future.failed[Source[ByteString, NotUsed]](new IllegalStateException(s"Could not get the contents of the object $name in the bucket $bucket. Response: ${response.status} - ${response.headers}"))
        }
      }).mapMaterializedValue(_ => NotUsed)
    }
  }

  def get = new RESTGetRequest(this)

  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = {
    def req = storage.request(
      Some(bucket), Some(name), requestTimeout = requestTimeout)

    req.head().flatMap { response =>
      if (response.status != 200) {
        Future.failed[Map[String, Seq[String]]](new IllegalArgumentException(s"Could not get the head of the object $name in the bucket $bucket. Response: ${response.status} - ${response.statusText}"))
      } else {
        Future(response.headers)
      }
    }
  }

  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = headers().map { headers =>
    headers.collect { case (key, value) if key.startsWith("x-amz-meta-") => key.stripPrefix("x-amz-meta-") -> value }
  }

  /**
   * A S3 PUT request.
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html and http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
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
    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None, metadata: Map[String, String] = Map.empty[String, String])(f: (A, Chunk) => Future[A])(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[A]] = {
      val th = size.filter(_ > 0).fold(threshold) { sz =>
        val partCount = sz /: threshold
        if (partCount <= maxPart) threshold else Bytes(sz / maxPart)
      }
      implicit def ec: ExecutionContext = m.executionContext

      def flowChunks = Streams.chunker[E].via(Streams.consumeAtLeast(th))

      val amzHeaders = metadata.map {
        case (key, value) => s"x-amz-meta-${key}" -> value
      }

      flowChunks.prefixAndTail(1).flatMapMerge[A, NotUsed](1, {
        case (Nil, _) => Source.empty[A]

        case (head, tail) => head.toList match {
          case (last @ Chunk.Last(_)) :: _ => // if first is last, single chunk
            Source.single(last).via(putSimple(Option(w.contentType), amzHeaders, z, f))

          case first :: _ => {
            def chunks = Source.single(first) ++ tail // push back the first
            def source = chunks.zip(initiateUpload(amzHeaders).
              flatMapConcat(Source.repeat /* same ID for all */ ))

            source.via(putMulti(Option(w.contentType), z, f))
          }
        }
      }).toMat(Sink.head[A]) { (_, mat) => mat }
    }
  }

  def put[E, A] = new RESTPutRequest[E, A](defaultMaxPart)

  private case class WSS3DeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
     */
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      def checkExists = if (ignoreExists) {
        Future.unit
      } else {
        exists.flatMap {
          case false => Future.failed[Unit](new IllegalArgumentException(s"Could not delete $bucket/$name: doesn't exist"))
          case true => Future.unit
        }
      }

      for {
        _ <- checkExists
        _ <- storage.request(Some(bucket), Some(name), requestTimeout = requestTimeout).delete().flatMap {
          case Successful(_) => Future.successful(logger.info(
            s"Successfully deleted the object $bucket/$name."))

          case response =>
            Future.failed[Unit](new IllegalStateException(s"Could not delete the object $bucket/$name. Response: ${response.status} - ${response.statusText}; ${response.body}"))
        }
      } yield ()
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  def delete: DeleteRequest = WSS3DeleteRequest()

  /**
   * @see #copyTo
   * @see #delete
   */
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
      _ <- copyTo(targetBucketName, targetObjectName).recoverWith {
        case reason =>
          targetObj.delete.ignoreIfNotExists().filter(_ => false).
            recoverWith { case _ => Future.failed[Unit](reason) }
      }
      _ <- delete() /* the previous reference */
    } yield ()
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectCOPY.html
   */
  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext): Future[Unit] =
    storage.request(Some(targetBucketName), Some(targetObjectName),
      requestTimeout = requestTimeout).
      addHttpHeaders("x-amz-copy-source" -> s"/$bucket/$name").
      put("").flatMap {
        case Successful(_) => Future.successful(logger.info(s"Successfully copied the object [$bucket/$name] to [$targetBucketName/$targetObjectName]."))

        case response => Future.failed[Unit](new IllegalStateException(s"Could not copy the object [$bucket/$name] to [$targetBucketName/$targetObjectName]. Response: ${response.status} - ${response.statusText}; ${response.body}"))
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
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html
   */
  private def putSimple[A](contentType: Option[String], metadata: Map[String, String], z: => A, f: (A, Chunk) => Future[A])(implicit m: Materializer): Flow[Chunk, A, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Flow[Chunk].limit(1).flatMapConcat { single =>
      val req = storage.request(Some(bucket), Some(name),
        requestTimeout = requestTimeout).
        addHttpHeaders((("Content-MD5" -> ContentMD5(single.data)) +: (
          metadata.toSeq)): _*)

      Source.fromFuture(
        withContentTypeHeader(req, contentType).put(single.data).flatMap {
          case Successful(_) => Future.successful(logger.debug(
            s"Completed the simple upload for $bucket/$name."))

          case response =>
            Future.failed[Unit](new IllegalStateException(s"Could not update the contents of the object $bucket/$name. Response: ${response.status} - ${response.statusText}; ${response.body}"))

        }.flatMap(_ => f(z, single)))
    }
  }

  /**
   * Creates an Flow that will upload the bytes.
   * It consumes of source of chunks (each tagged with the upload ID).
   *
   * @param contentType $contentTypeParam
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
   */
  private def putMulti[A](contentType: Option[String], z: => A, f: (A, Chunk) => Future[A])(implicit m: Materializer): Flow[(Chunk, String), A, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    @inline def zst = (Option.empty[String], List.empty[String], z)

    Flow.apply[(Chunk, String)].
      foldAsync[(Option[String], List[String], A)](zst) {
        case ((_, etags, st), (chunk, id)) =>
          (for {
            etag <- uploadPart(chunk.data, contentType, etags.size + 1, id)
            nst <- f(st, chunk)
          } yield (Some(id), (etag :: etags), nst))
      }.mapAsync[A](1) {
        case (Some(id), etags, res) =>
          completeUpload(etags.reverse, id).map(_ => res)

        case st => Future.failed[A](
          new IllegalStateException(s"invalid upload state: $st"))
      }
  }

  /**
   * Initiates a multi-part upload and returns the upload ID we're supposed to include when uploading parts later on.
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadInitiate.html
   */
  private def initiateUpload(metadata: Map[String, String])(implicit ec: ExecutionContext): Source[String, NotUsed] = Source fromFuture {
    storage.request(Some(bucket), Some(name), Some("uploads"),
      requestTimeout = requestTimeout).addHttpHeaders(metadata.toSeq: _*).
      post("").flatMap {
        case Successful(response) => {
          val xmlResponse = scala.xml.XML.loadString(response.body)
          val uploadId = (xmlResponse \ "UploadId").text

          logger.debug(s"Initiated a multi-part upload for $bucket/$name using the ID $uploadId.")

          Future.successful(uploadId)
        }

        case response =>
          Future.failed[String](new IllegalStateException(s"Could not initiate the upload for [$bucket/$name]. Response: ${response.status} - ${response.statusText}; ${response.body}"))
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
    partNumber: Int, uploadId: String)(implicit ec: ExecutionContext): Future[String] = {
    val req = storage.request(
      Some(bucket), Some(name),
      query = Some(s"partNumber=$partNumber&uploadId=$uploadId"),
      requestTimeout = requestTimeout).addHttpHeaders("Content-MD5" -> ContentMD5(bytes))

    withContentTypeHeader(req, contentType).
      put(bytes).flatMap {
        case Successful(Etag(value)) => Future.successful {
          logger.trace(s"Uploaded part $partNumber with ${bytes.length} bytes of the upload $uploadId for $bucket/$name ($value).")

          value
        }

        case response @ Successful(_) =>
          Future.failed[String](new IllegalStateException(s"Response for the upload [$bucket/$name, $uploadId, part: $partNumber] did not include an ETag header: ${response.headers}."))

        case response =>
          Future.failed[String](new IllegalStateException(s"Could not upload a part for [$bucket/$name, $uploadId, part: $partNumber]. Response: ${response.status} - ${response.statusText}; ${response.body}"))
      }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadComplete.html
   */
  private def completeUpload(etags: List[String], uploadId: String)(implicit ec: ExecutionContext): Future[Unit] = {
    import scala.xml.Elem

    storage.request(Some(bucket), Some(name),
      query = Some(s"uploadId=$uploadId"),
      requestTimeout = requestTimeout).post(
        <CompleteMultipartUpload>
          {
            etags.zipWithIndex.map[Elem, List[Elem]] {
              case (etag, partNumber) =>
                // Part numbers start at index 1 rather than 0
                <Part><PartNumber>{ partNumber + 1 }</PartNumber><ETag>{ etag }</ETag></Part>
            }
          }
        </CompleteMultipartUpload>).flatMap {
          case Successful(_) => Future.successful(logger.debug(
            s"Completed the upload $uploadId for $bucket/$name."))

          case response => Future.failed[Unit](new IllegalStateException(s"Could not complete the upload for [$bucket/$name, $uploadId]. Response: ${response.status} - ${response.statusText}; ${response.body}"))
        }
  }

  @inline private def withContentTypeHeader(req: StandaloneWSRequest, contentType: Option[String]): StandaloneWSRequest = contentType.fold(req)(c => req.addHttpHeaders("Content-Type" -> c))

  private object Etag {
    def unapply(response: StandaloneWSResponse): Option[String] =
      response.header("ETag")
  }

  override lazy val toString = s"WSS3ObjectRef($bucket, $name)"

  def versioning: Option[ObjectVersioning] = Some(this)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  private[s3] case class ObjectsVersions(
    maybeMax: Option[Long] = None, includeDeleteMarkers: Boolean = false) extends ref.VersionedListRequest {

    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def withDeleteMarkers: ObjectsVersions = this.copy(includeDeleteMarkers = true)

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def next(nextToken: String): Source[VersionedObject, NotUsed] =
        list(Some(nextToken))(next(_))

      list(Option.empty[String])(next(_))
    }

    def list(token: Option[String])(andThen: String => Source[VersionedObject, NotUsed])(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      val parse: Elem => Iterable[VersionedObject] = { xml =>
        if (includeDeleteMarkers) {
          (xml \ "Version").map(Xml.versionDecoder) ++ (xml \ "DeleteMarker").map(Xml.deleteMarkerDecoder)
        } else {
          (xml \ "Version").map(Xml.versionDecoder)
        }
      }

      val query: Option[String] => Option[String] = { token =>
        buildQuery(versionParam, prefixParam(ref.name), maxParam(maybeMax), tokenParam(token))
      }

      WSS3BucketRef.list[VersionedObject](ref.storage, ref.bucket, token)(query, parse, _.name, andThen)
    }
  }

  def versions: VersionedListRequest = ObjectsVersions(None)

  def version(versionId: String): VersionedObjectRef = WSS3VersionedObjectRef(storage, bucket, name, versionId)
}

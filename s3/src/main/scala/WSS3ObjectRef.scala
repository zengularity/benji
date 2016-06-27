package com.zengularity.s3

import scala.concurrent.{ ExecutionContext, Future }

import play.api.http.Status

import play.api.libs.iteratee.{ Enumerator, Enumeratee, Input, Iteratee }
import play.api.libs.ws.{ WSClient, WSRequest }

import com.zengularity.ws.{ ContentMD5, Successful }
import com.zengularity.storage.{ Bytes, ByteRange, ObjectRef, Streams }

final class WSS3ObjectRef private[s3] (
    val storage: WSS3,
    val bucket: String,
    val name: String,
    val headers: List[(String, String)] = Nil
) extends ObjectRef[WSS3] { ref =>

  /** The maximum number of part (10,000) for a multipart upload to S3/AWS. */
  val defaultMaxPart = 10000

  val defaultThreshold = Bytes.megabytes(5)

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html
   */
  def exists(implicit ec: ExecutionContext, ws: WSClient): Future[Boolean] =
    storage.request(Some(bucket), Some(name), requestTimeout = requestTimeout).
      head().map(_.status == Status.OK)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
   */
  final class RESTGetRequest(val target: ref.type) extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit ec: ExecutionContext, tr: Transport): Enumerator[Array[Byte]] = Enumerator.flatten {
      val req = storage.request(
        Some(bucket), Some(name), requestTimeout = requestTimeout
      )

      range.fold(req)(r => req.withHeaders(
        "Range" -> s"bytes=${r.start}-${r.end}"
      )).getStream().map {
        case (Successful(response), enumerator) => enumerator

        case (response, _) =>
          throw new IllegalStateException(s"Could not get the contents of the object $name in the bucket $bucket. Response: ${response.status} - ${response.headers}")
      }
    }
  }

  def get = new RESTGetRequest(this)

  /**
   * A S3 PUT request.
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html and http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
   *
   * @define maxPartParam the maximum number of part
   * @param maxPart $maxPartParam
   */
  final class RESTPutRequest[E, A](
      val maxPart: Int
  ) extends ref.PutRequest[E, A] {

    /**
     * Returns an updated request with the given maximum number of part.
     *
     * @param max $maxPartParam
     */
    def withMaxPart(max: Int) = new RESTPutRequest[E, A](max)

    /**
     * If the `size` is known and if the partitioning according that and the `threshold` would exceed the `maxPart`, then `size / maxPart` is used instead of the given threshold for multipart upload.
     */
    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None)(f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, ws: Transport, w: Writer[E]): Iteratee[E, A] = {
      val th = size.filter(_ > 0).fold(threshold) { sz =>
        val partCount = sz /: threshold
        if (partCount <= maxPart) threshold else Bytes(sz / maxPart)
      }

      w.toEnumeratee &>> Streams.consumeAtLeast(th).flatMapM { bytes =>
        if (th wasExceeded bytes) {
          // Knowing that we have at least one chunk that
          // is bigger than the threshold, it's safe to start
          // a multi-part upload with everything we consumed so far.
          putMulti(w.contentType, th, z, f).feed(Input.El(bytes))
        } else {
          // This one also needs to be fed Input.EOF to finish the upload,
          // we know that we've received Input.EOF as otherwise the threshold
          // would have been exceeded (or we wouldn't be in this function).
          for {
            a <- Future.successful(putSimple(w.contentType, z, f))
            b <- a.feed(Input.El(bytes))
            c <- b.feed(Input.EOF)
          } yield c
        }
      }
    }
  }

  def put[E, A] = new RESTPutRequest[E, A](defaultMaxPart)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
   */
  def delete(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] = {
    def failMsg = s"Could not delete the object $bucket/$name"

    exists.flatMap {
      case true => storage.request(Some(bucket), Some(name),
        requestTimeout = requestTimeout).delete().map {
        case Successful(response) =>
          logger.info(s"Successfully deleted the object $bucket/$name.")

        case response =>
          throw new IllegalStateException(s"$failMsg. Response: ${response.status} - ${response.statusText}; ${response.body}")
      }

      case _ => Future.failed[Unit](new IllegalArgumentException(
        s"$failMsg. Response: 404 - Object not found"
      ))
    }
  }

  /**
   * @see #copyTo
   * @see #delete
   */
  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] = {
    val targetObj = storage.bucket(targetBucketName).obj(targetObjectName)

    for {
      _ <- {
        if (!preventOverwrite) Future.successful({})
        else targetObj.exists.flatMap {
          case true => Future.failed[Unit](new IllegalStateException(
            s"Could not move $bucket/$name: target $targetBucketName/$targetObjectName already exists"
          ))

          case _ => Future.successful({})
        }
      }
      _ <- copyTo(targetBucketName, targetObjectName).recoverWith {
        case reason =>
          targetObj.delete.filter(_ => false).
            recoverWith { case _ => Future.failed[Unit](reason) }
      }
      _ <- delete // the previous reference
    } yield ()
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectCOPY.html
   */
  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] =
    storage.request(Some(targetBucketName), Some(targetObjectName),
      requestTimeout = requestTimeout).
      withHeaders("x-amz-copy-source" -> s"/$bucket/$name").
      put("").map {
        case Successful(response) =>
          logger.info(s"Successfully copied the object [$bucket/$name] to [$targetBucketName/$targetObjectName].")

        case response =>
          throw new IllegalStateException(s"Could not copy the object [$bucket/$name] to [$targetBucketName/$targetObjectName]. Response: ${response.status} - ${response.statusText}; ${response.body}")
      }

  // Utility methods

  /**
   * Creates an Iteratee that will upload the bytes it consumes in one request,
   * without streaming them.
   *
   * For this operation we need to know the overall content length
   * (the server requires that), which is why we have to buffer everything upfront.
   *
   * If you already know that your upload will exceed the threshold,
   * then use multi-part uploads.
   *
   * @param contentType $contentTypeParam
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html
   */
  private def putSimple[A](contentType: Option[String], z: => A, f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, ws: WSClient): Iteratee[Array[Byte], A] = Iteratee.consume[Array[Byte]]().mapM { bytes =>
    val req = storage.request(Some(bucket), Some(name),
      requestTimeout = requestTimeout).
      withHeaders("Content-MD5" -> ContentMD5(bytes))

    withContentTypeHeader(req, contentType).put(bytes).map {
      case Successful(response) => logger.debug(
        s"Completed the simple upload for $bucket/$name."
      )

      case response =>
        throw new IllegalStateException(s"Could not update the contents of the object $bucket/$name. Response: ${response.status} - ${response.statusText}; ${response.body}")
    }.flatMap(_ => f(z, bytes))
  }

  /**
   * Creates an Iteratee that will upload the bytes.
   * It consumes in multi-part uploads.
   *
   * @param contentType $contentTypeParam
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
   */
  private def putMulti[A](contentType: Option[String], threshold: Bytes, z: => A, f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, ws: WSClient): Iteratee[Array[Byte], A] = {
    Enumeratee.grouped(Streams.consumeAtLeast(threshold)) &>>
      Iteratee.flatten(initiateUpload.map { id =>
        Iteratee.foldM[Array[Byte], (List[String], A)](
          List.empty[String] -> z
        ) {
            case ((etags, st), bytes) => for {
              etag <- uploadPart(bytes, contentType, etags.size + 1, id)
              nst <- f(st, bytes)
            } yield (etag :: etags) -> nst
          }.mapM[A] {
            case (etags, res) =>
              completeUpload(etags.reverse, id).map(_ => res)
          }
      })
  }

  /**
   * Initiates a multi-part upload and returns the upload ID we're supposed to include when uploading parts later on.
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadInitiate.html
   */
  private def initiateUpload(implicit ec: ExecutionContext, ws: WSClient): Future[String] = storage.request(Some(bucket), Some(name), Some("uploads"),
    requestTimeout = requestTimeout).post("").map {
    case Successful(response) =>
      val xmlResponse = scala.xml.XML.loadString(response.body)
      val uploadId = (xmlResponse \ "UploadId").text

      logger.debug(s"Initiated a multi-part upload for $bucket/$name using the ID $uploadId.")
      uploadId

    case response => throw new IllegalStateException(s"Could not initiate the upload for [$bucket/$name]. Response: ${response.status} - ${response.statusText}; ${response.body}")
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
  private def uploadPart(bytes: Array[Byte], contentType: Option[String], partNumber: Int, uploadId: String)(implicit ec: ExecutionContext, ws: WSClient): Future[String] = {
    val req = storage.request(
      Some(bucket), Some(name),
      query = Some(s"partNumber=$partNumber&uploadId=$uploadId"),
      requestTimeout = requestTimeout
    ).withHeaders("Content-MD5" -> ContentMD5(bytes))

    withContentTypeHeader(req, contentType).put(bytes).map {
      case Successful(response) =>
        logger.trace(s"Uploaded part $partNumber with ${bytes.length} bytes of the upload $uploadId for $bucket/$name.")

        response.header("ETag").getOrElse(throw new IllegalStateException(
          s"Response for the upload [$bucket/$name, $uploadId, part: $partNumber] did not include an ETag header: ${response.allHeaders}."
        ))

      case response =>
        throw new IllegalStateException(s"Could not upload a part for [$bucket/$name, $uploadId, part: $partNumber]. Response: ${response.status} - ${response.statusText}; ${response.body}")
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadComplete.html
   */
  private def completeUpload(etags: List[String], uploadId: String)(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] = {
    storage.request(Some(bucket), Some(name),
      query = Some(s"uploadId=$uploadId"),
      requestTimeout = requestTimeout).post(
        <CompleteMultipartUpload>
          {
            etags.zipWithIndex.map({
              case (etag, partNumber) =>
                // Part numbers start at index 1 rather than 0
                <Part><PartNumber>{ partNumber + 1 }</PartNumber><ETag>{ etag }</ETag></Part>
            })
          }
        </CompleteMultipartUpload>
      ).map {
          case Successful(_) => logger.debug(
            s"Completed the upload $uploadId for $bucket/$name."
          )

          case response =>
            throw new IllegalStateException(
              s"Could not complete the upload for [$bucket/$name, $uploadId]. Response: ${response.status} - ${response.statusText}; ${response.body}"
            )
        }
  }

  @inline private def withContentTypeHeader(req: WSRequest, contentType: Option[String]): WSRequest = contentType.fold(req)(c => req.withHeaders("Content-Type" -> c))
}

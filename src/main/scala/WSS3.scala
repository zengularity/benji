package com.zengularity.s3

import org.apache.commons.codec.digest.DigestUtils
import org.joda.time.DateTime

import play.api.Logger
import play.api.http.{ Writeable, Status }
import play.api.libs.iteratee._
import play.api.libs.ws.{ WSRequest, WSClient, WSResponseHeaders, WSResponse }

import scala.concurrent.{ Future, ExecutionContext }

case class Bucket(name: String, creationDate: DateTime)

case class Object(key: String, bytes: Long, lastModifiedAt: DateTime)

/**
 * Implementation of the S3 API using Play's WS library.
 *
 * @define contentTypeParam the MIME type of content
 * @define putSizeParam the total size in bytes to be PUTed
 */
class WSS3(requestBuilder: WSRequestBuilder, requestTimeout: Option[Long] = None) {

  /** Logger instance for this class. */
  private val logger = Logger("com.zengularity.s3")

  /** The maximum number of part (10,000) for a multipart upload to S3/AWS. */
  val AWSPartLimit = 10000

  import requestBuilder.request

  /**
   * Returns a WS/S3 instance with specified request timeout.
   * @param requestTimeout the request timeout for client instance
   */
  def withRequestTimeout(requestTimeout: Long): WSS3 =
    new WSS3(requestBuilder, Some(requestTimeout))

  /**
   * Returns a list of all buckets
   * owned by the authenticated sender of the request.
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTServiceGET.html
   */
  def buckets(implicit ec: ExecutionContext, ws: WSClient): Future[List[Bucket]] = request(requestTimeout = requestTimeout).get().map {
    case Successful(response) =>
      val xmlResponse = scala.xml.XML.loadString(response.body)
      val buckets = xmlResponse \ "Buckets" \ "Bucket"

      buckets.map({ bucket =>
        Bucket(
          name = (bucket \ "Name").text,
          creationDate =
            DateTime.parse((bucket \ "CreationDate").text)
        )
      }).toList

    case response => throw new IllegalStateException(
      s"Could not get a list of all buckets. Response: ${response.status} - ${response.statusText}; ${response.body}"
    )
  }

  /**
   * Bucket selection, for operations scoped from there.
   *
   * @param bucketName the name of the bucket to select
   */
  case class bucket(bucketName: String) {
    /**
     * Allows you to retrieve a list of all objects within this bucket.
     * @see @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
     */
    def objects(implicit ec: ExecutionContext, ws: WSClient): Future[List[Object]] = request(bucketName, requestTimeout = requestTimeout).get().map {
      case Successful(response) =>
        val xmlResponse = scala.xml.XML.loadString(response.body)
        val contents = xmlResponse \ "Contents"
        contents.map({ content =>
          Object(
            key = (content \ "Key").text,
            bytes = (content \ "Size").text.toLong,
            lastModifiedAt =
              DateTime.parse((content \ "LastModified").text)
          )
        }).toList

      case response =>
        throw new IllegalStateException(s"Could not list all objects within the bucket $bucketName. Response: ${response.status} - ${response.statusText}; ${response.body}")
    }

    /**
     * Determines whether or not this bucket exists. `false` might be returned also in
     * cases where you don't have permission to view a certain bucket.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketHEAD.html
     */
    def exists(implicit ec: ExecutionContext, ws: WSClient): Future[Boolean] =
      request(
        bucketName,
        requestTimeout = requestTimeout
      ).head().map(_.status == Status.OK)

    /**
     * Creates this bucket.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUT.html
     */
    def create(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] =
      request(
        bucketName,
        requestTimeout = requestTimeout
      ).put("").map {
        case Successful(response) =>
          logger.info(s"Successfully created the bucket $bucketName.")

        case response =>
          throw new IllegalStateException(s"Could not create the bucket $bucketName. Response: ${response.status} - ${response.statusText}; ${response.body}")

      }

    /**
     * Deletes this bucket.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketDELETE.html
     */
    def delete(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] =
      request(bucketName, requestTimeout = requestTimeout).delete().map {
        case Successful(response) =>
          logger.info(s"Successfully deleted the bucket $bucketName.")

        case response =>
          throw new IllegalStateException(s"Could not delete the bucket $bucketName. Response: ${response.status} - ${response.statusText}; ${response.body}")
      }

    /**
     * Selects the given object within this bucket for further operations.
     * @param objectName the name of child object to select
     */
    def obj(objectName: String): WSS3Object =
      new WSS3Object(bucketName, objectName)
  }

  /**
   * Object selection.
   *
   * @param bucketName the name of parent bucket
   * @param objectName the name of the selected object
   * @param headers the list of HTTP headers (key -> value)
   */
  case class WSS3Object(
      bucketName: String,
      objectName: String,
      headers: List[(String, String)] = Nil
  ) {
    // S3Object methods

    /**
     * Returns a new object selection, using the specified content range.
     * @param range the value for the `Range` header
     */
    def withRange(range: String) = copy(headers = ("Range" -> range) :: headers)

    /**
     * Determines whether or not this object exists.
     * `false` might be returned also in
     * cases where you don't have permission to view a certain object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html
     */
    def exists(implicit ec: ExecutionContext, ws: WSClient): Future[Boolean] =
      request(bucketName, objectName,
        requestTimeout = requestTimeout).head().map(_.status == Status.OK)

    /**
     * Allows you to retrieve the contents of this object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
     */
    def get(implicit ec: ExecutionContext, ws: WSClient): Enumerator[Array[Byte]] = Enumerator.flatten(request(bucketName, objectName,
      requestTimeout = requestTimeout).
      withHeaders(headers: _*).getStream().map {
        case (Successful(response), enumerator) => enumerator

        case (response, _) =>
          throw new IllegalStateException(s"Could not get the contents of the object $objectName in the bucket $bucketName. Response: ${response.status} - ${response.headers}")
      })

    def put[E](implicit ec: ExecutionContext, ws: WSClient, wrt: Writeable[E]): Iteratee[E, Unit] = put({})((_, _) => Future.successful({}))

    /**
     * @param size $putSizeParam
     */
    def put[E](size: Long)(implicit ec: ExecutionContext, ws: WSClient, wrt: Writeable[E]): Iteratee[E, Unit] = put({}, size = size)((_, _) => Future.successful({}))

    /**
     * @param size $putSizeParam
     * @param maxPart the maximum number of part (if using multipart upload)
     */
    def put[E](size: Long, maxPart: Int)(implicit ec: ExecutionContext, ws: WSClient, wrt: Writeable[E]): Iteratee[E, Unit] = put({}, size = size, maxPart = maxPart)((_, _) => Future.successful({}))

    /**
     * Allows you to update the contents of this object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html and http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
     *
     * If the `size` is known and if the partitioning according that and the `threshold` would exceed the `maxPart`, then `size / maxPart` is used instead of the given threshold for multipart upload.
     *
     * @param threshold the multipart threshold (by default 5MB)
     * @param size $putSizeParam (by default -1L for unknown)
     * @param maxPart the maximum number of part (if using multipart upload)
     */
    def put[E, A](z: => A, threshold: Bytes = Bytes.megabytes(5), size: Long = -1L, maxPart: Int = AWSPartLimit)(f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, ws: WSClient, wrt: Writeable[E]): Iteratee[E, A] = {
      val th = if (size < 0) threshold else {
        val partCount = size /: threshold

        if (partCount <= maxPart) threshold else Bytes(size / maxPart)
      }

      wrt.toEnumeratee &>> Iteratees.consumeAtLeast(th).flatMapM { bytes =>
        if (th wasExceeded bytes) {
          // Knowing that we have at least one chunk that
          // is bigger than 5 MB, it's safe to start
          // a multi-part upload with everything we consumed so far.
          putMulti(wrt.contentType, th, z, f).feed(Input.El(bytes))
        } else {
          // This one also needs to be fed Input.EOF to finish the upload,
          // we know that we've received Input.EOF as otherwise the threshold
          // would have been exceeded (or we wouldn't be in this function).
          for {
            a <- Future.successful(putSimple(wrt.contentType, z, f))
            b <- a.feed(Input.El(bytes))
            c <- b.feed(Input.EOF)
          } yield c
        }
      }
    }

    /**
     * Deletes the referenced object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
     */
    def delete(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] =
      request(bucketName, objectName, requestTimeout = requestTimeout).
        delete().map {
          case Successful(response) =>
            logger.info(
              s"Successfully deleted the object $bucketName/$objectName."
            )

          case response =>
            throw new IllegalStateException(s"Could not delete the object $bucketName/$objectName. Response: ${response.status} - ${response.statusText}; ${response.body}")
        }

    /**
     * Copies the referenced object to another one.
     *
     * @param target the reference to the target object
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectCOPY.html
     */
    def copyTo(target: WSS3#WSS3Object)(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] = target match {
      case WSS3Object(targetBucketName, targetObjectName, _) =>
        copyTo(targetBucketName, targetObjectName)

      case otherwise =>
        throw new IllegalArgumentException(
          s"Target object you specified [$target] is unknown."
        )
    }

    /**
     * Copies the referenced object to another location.
     *
     * @param targetBucketName the name of the parent bucket for the target object
     * @param targetObjectName the name of the target object
     */
    def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] =
      request(targetBucketName, targetObjectName,
        requestTimeout = requestTimeout).
        withHeaders("x-amz-copy-source" -> s"/$bucketName/$objectName").
        put("").map {
          case Successful(response) =>
            logger.info(s"Successfully copied the object [$bucketName/$objectName] to [$targetBucketName/$targetObjectName].")

          case response =>
            throw new IllegalStateException(s"Could not copy the object [$bucketName/$objectName] to [$targetBucketName/$targetObjectName]. Response: ${response.status} - ${response.statusText}; ${response.body}")
        }

    // Utility methods

    /**
     * Creates an Iteratee that will upload the bytes it consumes in one request, without streaming them.
     * For this operation we need to know the overall content length
     * (the server requires that), which is why we have to buffer everything upfront.
     *
     * If you already know that your upload will exceed 5 megabyte,
     * use multi-part uploads.
     *
     * @param contentType $contentTypeParam
     *
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html
     */
    private def putSimple[A](contentType: Option[String], z: => A, f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, ws: WSClient): Iteratee[Array[Byte], A] = Iteratee.consume[Array[Byte]]().mapM { bytes =>
      request(bucketName, objectName, requestTimeout = requestTimeout).
        withContentMD5Header(bytes).
        withContentTypeHeader(contentType).put(bytes).map {
          case Successful(response) => logger.debug(
            s"Completed the simple upload for $bucketName/$objectName."
          )

          case response =>
            throw new IllegalStateException(s"Could not update the contents of the object $bucketName/$objectName. Response: ${response.status} - ${response.statusText}; ${response.body}")
        }.flatMap(_ => f(z, bytes))
    }

    /**
     * Creates an Iteratee that will upload the bytes.
     * It consumes in multi-part uploads.
     *
     * @param contentType $contentTypeParam
     * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
     */
    private def putMulti[A](contentType: Option[String], threshold: Bytes, z: => A, f: (A, Array[Byte]) => Future[A])(implicit ec: ExecutionContext, ws: WSClient): Iteratee[Array[Byte], A] = Enumeratee.grouped(
      Iteratees.consumeAtLeast(threshold)
    ) &>>
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

    /**
     * Initiates a multi-part upload and returns the upload ID we're supposed to include when uploading parts later on.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadInitiate.html
     */
    private def initiateUpload(implicit ec: ExecutionContext, ws: WSClient): Future[String] = request(bucketName, objectName, "uploads",
      requestTimeout = requestTimeout).post("").map {
      case Successful(response) =>
        val xmlResponse = scala.xml.XML.loadString(response.body)
        val uploadId = (xmlResponse \ "UploadId").text

        logger.debug(s"Initiated a multi-part upload for $bucketName/$objectName using the ID $uploadId.")
        uploadId

      case response => throw new IllegalStateException(s"Could not initiate the upload for [$bucketName/$objectName]. Response: ${response.status} - ${response.statusText}; ${response.body}")
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
    private def uploadPart(bytes: Array[Byte], contentType: Option[String], partNumber: Int, uploadId: String)(implicit ec: ExecutionContext, ws: WSClient): Future[String] = request(
      bucketName, objectName, s"partNumber=$partNumber&uploadId=$uploadId",
      requestTimeout = requestTimeout
    ).withContentMD5Header(bytes).withContentTypeHeader(contentType).
      put(bytes).map {
        case Successful(response) =>
          logger.trace(s"Uploaded part $partNumber with ${bytes.length} bytes of the upload $uploadId for $bucketName/$objectName.")

          response.header("ETag").getOrElse(throw new IllegalStateException(
            s"Response for the upload [$bucketName/$objectName, $uploadId, part: $partNumber] did not include an ETag header: ${response.allHeaders}."
          ))

        case response =>
          throw new IllegalStateException(s"Could not upload a part for [$bucketName/$objectName, $uploadId, part: $partNumber]. Response: ${response.status} - ${response.statusText}; ${response.body}")
      }

    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadComplete.html
     */
    private def completeUpload(etags: List[String], uploadId: String)(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] = {
      request(bucketName, objectName, query = s"uploadId=$uploadId",
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
            s"Completed the upload $uploadId for $bucketName/$objectName."
          )

          case response =>
            throw new IllegalStateException(
              s"Could not complete the upload for [$bucketName/$objectName, $uploadId]. Response: ${response.status} - ${response.statusText}; ${response.body}"
            )
        }
    }
  }

  /** To make code more descriptive .. we just want to match on succesful responses after all. */
  private object Successful {
    // The S3 REST API only ever returns OK or NO_CONTENT ...
    // which is why I'll only check these two.
    def unapply(response: WSResponse): Option[WSResponse] = {
      if (response.status == Status.OK || response.status == Status.PARTIAL_CONTENT || response.status == Status.NO_CONTENT) {
        Some(response)
      } else None
    }

    def unapply(headers: WSResponseHeaders): Option[WSResponseHeaders] = {
      if (headers.status == Status.OK ||
        headers.status == Status.PARTIAL_CONTENT ||
        headers.status == Status.NO_CONTENT) {
        Some(headers)
      } else None
    }
  }

  private implicit class RichWSRequestHolder(self: WSRequest) {
    def withContentMD5Header(bytes: Array[Byte]): WSRequest =
      self.withHeaders("Content-MD5" -> ContentMD5(bytes))

    def withContentTypeHeader(contentType: Option[String]): WSRequest =
      contentType.fold(self)(c => self.withHeaders("Content-Type" -> c))
  }

  /**
   * Allows you to calculate the 'Content-MD5' header for any upload. Include this to avoid data corruption over the network.
   */
  private object ContentMD5 {
    def apply(bytes: Array[Byte]): String =
      com.ning.http.util.Base64.encode(DigestUtils.md5(bytes))
  }
}

/** S3 companion */
object S3 {
  /**
   * Returns the S3 client in the path style.
   *
   * @param accessKeyId the user access key
   * @param secretAccessKeyId the user secret key
   * @param scheme the scheme
   * @param host the host name (or IP address)
   * @return A WSS3 instance configured to work with the S3-compatible API of a the server
   */
  def apply(accessKeyId: String, secretAccessKeyId: String, scheme: String, host: String): WSS3 = new WSS3(new PathStyleWSRequestBuilder(new SignatureCalculator(accessKeyId, secretAccessKeyId, host), new java.net.URL(s"${scheme}://${host}")))

  /**
   * Returns the S3 client in the virtual host style.
   *
   * @param accessKeyId the user access key
   * @param secretAccessKeyId the user secret key
   * @param scheme the scheme
   * @param host the host name (or IP address)
   * @return A WSS3 instance configured to work with the S3-compatible API of a the server
   */
  def virtualHost(accessKeyId: String, secretAccessKeyId: String, scheme: String, host: String): WSS3 = new WSS3(new VirtualHostWSRequestBuilder(new SignatureCalculator(accessKeyId, secretAccessKeyId, host), new java.net.URL(s"${scheme}://${host}")))

  def apply(requestBuilder: WSRequestBuilder)(implicit ec: ExecutionContext, ws: WSClient): WSS3 = new WSS3(requestBuilder)
}

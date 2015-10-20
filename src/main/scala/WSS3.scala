package com.zengularity.s3

import org.joda.time.DateTime

import play.api.{ Application, Logger }
import play.api.http.{ Writeable, Status }
import play.api.libs.iteratee._
import play.api.libs.ws.{ WSRequestHolder, WSResponseHeaders, WSResponse }

import scala.concurrent.{ Future, ExecutionContext }

case class Bucket(name: String, creationDate: DateTime)

case class Object(key: String, bytes: Long, lastModifiedAt: DateTime)

/**
 * Implementation of the S3 API using Play's WS library.
 */
class WSS3(requestBuilder: WSRequestBuilder)(implicit ec: ExecutionContext, app: Application) {

  /** Logger instance for this class. */
  private val logger = Logger("com.zengularity.s3")

  import requestBuilder.request

  /**
   * Returns a list of all buckets
   * owned by the authenticated sender of the request.
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTServiceGET.html
   */
  def buckets: Future[List[Bucket]] = request().get().map {
    case Successful(response) => {
      val xmlResponse = scala.xml.XML.loadString(response.body)
      val buckets = xmlResponse \ "Buckets" \ "Bucket"

      buckets.map({ bucket =>
        Bucket(
          name = (bucket \ "Name").text,
          creationDate =
            DateTime.parse((bucket \ "CreationDate").text)
        )
      }).toList
    }

    case response => throw new IllegalStateException(
      s"Could not get a list of all buckets. Response (${response.status} / ${response.statusText}): ${response.body}"
    )
  }

  /**
   * Selects the given bucket for further operations.
   */
  case class bucket(bucketName: String) {
    /**
     * Allows you to retrieve a list of all objects within this bucket.
     * @see @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
     */
    def objects: Future[List[Object]] = request(bucketName).get().map {
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
        throw new IllegalStateException(s"Could not list all objects within the bucket $bucketName. Response (${response.status} / ${response.statusText}): ${response.body}")
    }

    /**
     * Determines whether or not this bucket exists. `false` might be returned also in
     * cases where you don't have permission to view a certain bucket.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketHEAD.html
     */
    def exists: Future[Boolean] =
      request(bucketName).head().map(_.status == Status.OK)

    /**
     * Creates this bucket.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUT.html
     */
    def create: Future[Unit] = request(bucketName).put("").map {
      case Successful(response) =>
        logger.info(s"Successfully created the bucket $bucketName.")

      case response =>
        throw new IllegalStateException(s"Could not create the bucket $bucketName. Response (${response.status} / ${response.statusText}): ${response.body}")

    }

    /**
     * Deletes this bucket.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketDELETE.html
     */
    def delete: Future[Unit] = request(bucketName).delete().map {
      case Successful(response) =>
        logger.info(s"Successfully deleted the bucket $bucketName.")

      case response =>
        throw new IllegalStateException(s"Could not delete the bucket $bucketName. Response (${response.status} / ${response.statusText}): ${response.body}")
    }

    /**
     * Selects the given object within this bucket for further operations.
     */
    def obj(objectName: String): WSS3Object =
      new WSS3Object(bucketName, objectName)
  }

  case class WSS3Object(bucketName: String, objectName: String) {
    // S3Object methods

    /**
     * Determines whether or not this object exists.
     * `false` might be returned also in
     * cases where you don't have permission to view a certain object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html
     */
    def exists: Future[Boolean] =
      request(bucketName, objectName).head().map(_.status == Status.OK)

    /**
     * Allows you to retrieve the contents of this object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
     */
    def get: Enumerator[Array[Byte]] = Enumerator.flatten(
      request(bucketName, objectName).getStream().map {
        case (Successful(response), enumerator) => enumerator

        case (response, _) =>
          throw new IllegalStateException(s"Could not get the contents of the object $objectName in the bucket $bucketName. Response (${response.status})")
      }
    )

    /**
     * Allows you to update the contents of this object.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html and http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
     */
    def put[A](implicit wrt: Writeable[A]): Iteratee[A, Unit] = {
      // Initially we'll be buffering up to 5 MB, because we might have to upload the contents in
      // one big request. If we exceed that size, however, we'll switch to a multi-part upload and
      // stop buffering all the incoming bytes.
      val threshold = Bytes.megabytes(5)

      wrt.toEnumeratee &>>
        Iteratees.consumeAtLeast(threshold).flatMapM({ bytes =>
          if (threshold.wasExceeded(bytes)) {
            // Knowing that we have at least one chunk that is bigger than 5 MB, it's safe to start
            // a multi-part upload with everything we consumed so far.
            putMulti(wrt.contentType).feed(Input.El(bytes))
          } else {
            // This one also needs to be fed Input.EOF to finish the upload, we know that we've received
            // Input.EOF as otherwise the threshold would have been exceeded (or we wouldn't be in this
            // function).
            for {
              a <- Future.successful(putSimple(wrt.contentType))
              b <- a.feed(Input.El(bytes))
              c <- b.feed(Input.EOF)
            } yield c
          }
        })
    }

    /**
     * Deletes this object
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
     */
    def delete: Future[Unit] = request(bucketName, objectName).delete().map {
      case Successful(response) =>
        logger.info(s"Successfully deleted the object $bucketName/$objectName.")

      case response =>
        throw new IllegalStateException(s"Could not delete the object $bucketName/$objectName. Response (${response.status} / ${response.statusText}): ${response.body}")
    }

    /**
     * Copies this object to another one.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectCOPY.html
     */
    def copyTo(target: WSS3#WSS3Object): Future[Unit] = target match {
      case WSS3Object(targetBucketName, targetObjectName) =>
        copyTo(targetBucketName, targetObjectName)

      case otherwise =>
        throw new IllegalArgumentException(s"Target object you specified [$target] is unknown.")
    }

    def copyTo(targetBucketName: String, targetObjectName: String): Future[Unit] = request(targetBucketName, targetObjectName).
      withHeaders("x-amz-copy-source" -> s"/$bucketName/$objectName").
      put("").map {
        case Successful(response) =>
          logger.info(s"Successfully copied the object [$bucketName/$objectName] to [$targetBucketName/$targetObjectName].")

        case response =>
          throw new IllegalStateException(s"Could not copy the object [$bucketName/$objectName] to [$targetBucketName/$targetObjectName]. Response (${response.status} / ${response.statusText}): ${response.body}")
      }

    // Utility methods

    /**
     * Creates an Iteratee that will upload the bytes it consumes in one request, without streaming them.
     * For this operation we need to know the overall content length (the server requires that), which is
     * why we have to buffer everything upfront.
     *
     * If you already know that your upload will exceed 5 megabyte, use multi-part uploads.
     *
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPUT.html
     */
    private def putSimple(contentType: Option[String]): Iteratee[Array[Byte], Unit] = Iteratee.consume[Array[Byte]]().mapM { bytes =>
      request(bucketName, objectName).
        withContentMD5Header(bytes).
        withContentTypeHeader(contentType).put(bytes).map {
          case Successful(response) => logger.debug(
            s"Completed the simple upload for $bucketName/$objectName."
          )

          case response =>
            throw new IllegalStateException(s"Could not update the contents of the object $bucketName/$objectName. Response (${response.status} / ${response.statusText}): ${response.body}")
        }
    }

    /**
     * Creates an Iteratee that will upload the bytes it consumes in multi-part uploads.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/mpuoverview.html
     */
    private def putMulti(contentType: Option[String]): Iteratee[Array[Byte], Unit] = {
      // Each part we're uploading, apart from the last one, must have at least 5 megabytes
      Enumeratee.grouped(Iteratees.consumeAtLeast(Bytes.megabytes(5))) &>>
        Iteratee.flatten(initiateUpload.map { uploadId =>
          Iteratee.foldM[Array[Byte], List[String]](Nil) {
            case (etags, bytes) =>
              uploadPart(bytes, contentType, etags.size + 1, uploadId).
                map { _ :: etags }
          }.mapM { etags => completeUpload(etags.reverse, uploadId) }
        })
    }

    /**
     * Initiates a multi-part upload and returns the upload ID we're supposed to include when uploading parts later on.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadInitiate.html
     */
    private def initiateUpload: Future[String] =
      request(bucketName, objectName, "uploads").post("").map {
        case Successful(response) => {
          val xmlResponse = scala.xml.XML.loadString(response.body)
          val uploadId = (xmlResponse \ "UploadId").text
          logger.debug(s"Initiated a multi-part upload for $bucketName/$objectName using the ID $uploadId.")
          uploadId
        }

        case response => throw new IllegalStateException(s"Could not initiate the upload for [$bucketName/$objectName] Response (${response.status} / ${response.statusText}}): ${response.body}")
      }

    /**
     * Uploads a part in a multi-part upload. Note that each part (the bytes) needs to be bigger than 5 MB, except the last one.
     * It returns the ETag header returned for that uploaded part, something that we'll need to keep track of to finish
     * the upload later on.
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadUploadPart.html
     */
    private def uploadPart(bytes: Array[Byte], contentType: Option[String], partNumber: Int, uploadId: String): Future[String] = request(bucketName, objectName,
      s"partNumber=$partNumber&uploadId=$uploadId").
      withContentMD5Header(bytes).
      withContentTypeHeader(contentType).put(bytes).map {
        case Successful(response) => {
          logger.trace(s"Uploaded part $partNumber with ${bytes.length} bytes of the upload $uploadId for $bucketName/$objectName.")

          response.header("ETag").getOrElse(throw new IllegalStateException(
            s"Response for the upload [$bucketName/$objectName, $uploadId, part: $partNumber] did not include an ETag header: ${response.allHeaders}."
          ))
        }

        case response =>
          throw new IllegalStateException(s"Could not upload a part for [$bucketName/$objectName, $uploadId, part: $partNumber] Response (${response.status} / ${response.statusText}}): ${response.body}")
      }

    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/mpUploadComplete.html
     */
    private def completeUpload(etags: List[String], uploadId: String): Future[Unit] = {
      request(bucketName, objectName, query = s"uploadId=$uploadId").post(
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
              s"Could not complete the upload for [$bucketName/$objectName, $uploadId]; Response (${response.status} / ${response.statusText}}): ${response.body}"
            )
        }
    }
  }

  /** To make code more descriptive .. we just want to match on succesful responses after all. */
  private object Successful {
    // The S3 REST API only ever returns OK or NO_CONTENT .. which is why I'll only check these two.
    def unapply(response: WSResponse): Option[WSResponse] = {
      if (response.status == Status.OK ||
        response.status == Status.NO_CONTENT) {
        Some(response)
      } else None
    }

    def unapply(headers: WSResponseHeaders): Option[WSResponseHeaders] = {
      if (headers.status == Status.OK || headers.status == Status.NO_CONTENT) {
        Some(headers)
      } else None
    }
  }

  private implicit class RichWSRequestHolder(self: WSRequestHolder) {
    def withContentMD5Header(bytes: Array[Byte]): WSRequestHolder =
      self.withHeaders("Content-MD5" -> ContentMD5(bytes))

    def withContentTypeHeader(contentType: Option[String]): WSRequestHolder =
      contentType.fold(self)(c => self.withHeaders("Content-Type" -> c))
  }

  /**
   * Allows you to calculate the 'Content-MD5' header for any upload. Include this to avoid data corruption over the network.
   */
  private object ContentMD5 {
    private val md5 = java.security.MessageDigest.getInstance("MD5")

    def apply(bytes: Array[Byte]): String =
      com.ning.http.util.Base64.encode(md5.digest(bytes))
  }
}

/** S3 companion */
object S3 {
  /**
   * @param accessKeyId CEPH user access key
   * @param secretAccessKeyId CEPH user secret key
   * @param server CEPH server URL
   * @return A WSS3 instance configured to work with the S3-compatible API of a CEPH server
   */
  def apply(accessKeyId: String, secretAccessKeyId: String, scheme: String, host: String)(implicit ec: ExecutionContext, app: Application): WSS3 =
    new WSS3(new PathStyleWSRequestBuilder(new SignatureCalculator(accessKeyId, secretAccessKeyId, host), new java.net.URL(scheme + "://" + host)))

  def apply(calculator: SignatureCalculator, scheme: String = "https", host: String = "s3.amazonaws.com")(implicit ec: ExecutionContext, app: Application): WSS3 = new WSS3(new VirtualHostWSRequestBuilder(calculator, new java.net.URL(scheme + "://" + host)))

  def apply(requestBuilder: WSRequestBuilder)(implicit ec: ExecutionContext, app: Application): WSS3 = new WSS3(requestBuilder)
}

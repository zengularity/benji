package com.zengularity.s3

import scala.concurrent.ExecutionContext

import org.joda.time.DateTime

import play.api.http.Writeable
import play.api.libs.iteratee.Enumerator
import play.api.libs.ws.{ WSRequest, WSClient }

import com.zengularity.ws.Successful
import com.zengularity.storage.{ Bucket, ObjectStorage, StoragePack }

object WSS3StoragePack extends StoragePack {
  type Transport = WSClient
  type Writer[T] = Writeable[T]
}

/**
 * Implementation of the S3 API for Object Storage using Play's WS library.
 *
 * @param requestBuilder the request builder
 * @param requestTimeout the optional timeout for the prepared requests
 * @define contentTypeParam the MIME type of content
 */
class WSS3(
    requestBuilder: WSRequestBuilder,
    val requestTimeout: Option[Long] = None
) extends ObjectStorage[WSS3] { self =>

  type Pack = WSS3StoragePack.type
  type ObjectRef = WSS3ObjectRef

  private[s3] def request(bucketName: Option[String] = None, objectName: Option[String] = None, query: Option[String] = None, requestTimeout: Option[Long] = None)(implicit tr: WSClient): WSRequest = {
    val req = requestBuilder(tr, bucketName, objectName, query)

    requestTimeout.fold(req)(req.withRequestTimeout)
  }

  def withRequestTimeout(timeout: Long): WSS3 =
    new WSS3(requestBuilder, Some(timeout))

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTServiceGET.html
   */
  object buckets extends self.BucketsRequest {
    def apply()(implicit ec: ExecutionContext, tr: WSClient): Enumerator[Bucket] = Enumerator.flatten(request(requestTimeout = requestTimeout).get().map {
      case Successful(response) =>
        val xmlResponse = scala.xml.XML.loadString(response.body)
        val buckets = xmlResponse \ "Buckets" \ "Bucket"

        Enumerator.enumerate(buckets.map({ bucket =>
          Bucket(
            name = (bucket \ "Name").text,
            creationTime = DateTime.parse((bucket \ "CreationDate").text)
          )
        }).toList)

      case response => throw new IllegalStateException(
        s"Could not get a list of all buckets. Response: ${response.status} - ${response.statusText}; ${response.body}"
      )
    })

    // TODO: Use pagination
  }

  def bucket(name: String): WSS3BucketRef = new WSS3BucketRef(this, name)
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

  def apply(requestBuilder: WSRequestBuilder): WSS3 = new WSS3(requestBuilder)
}

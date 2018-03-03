package com.zengularity.benji.s3

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.{ BodyWritable, StandaloneWSRequest, StandaloneWSResponse }

import com.zengularity.benji.{ Bucket, ObjectStorage, StoragePack }

object WSS3StoragePack extends StoragePack {
  type Transport = StandaloneAhcWSClient
  type Writer[T] = BodyWritable[T]
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
  val requestTimeout: Option[Long] = None) extends ObjectStorage[WSS3] {
  self =>

  import scala.concurrent.duration._

  type Pack = WSS3StoragePack.type
  type ObjectRef = WSS3ObjectRef

  private[s3] def request(bucketName: Option[String] = None, objectName: Option[String] = None, query: Option[String] = None, requestTimeout: Option[Long] = None)(implicit tr: StandaloneAhcWSClient): StandaloneWSRequest = {
    val req = requestBuilder(tr, bucketName, objectName, query)

    requestTimeout.fold(req) { t => req.withRequestTimeout(t.milliseconds) }
  }

  def withRequestTimeout(timeout: Long): WSS3 =
    new WSS3(requestBuilder, Some(timeout))

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTServiceGET.html
   */
  object buckets extends self.BucketsRequest {
    def apply()(implicit m: Materializer, tr: StandaloneAhcWSClient): Source[Bucket, NotUsed] =
      S3.getXml[Bucket](request(requestTimeout = requestTimeout))({ xml =>
        def buckets = xml \ "Buckets" \ "Bucket"

        Source(buckets.map { bucket =>
          Bucket(
            name = (bucket \ "Name").text,
            creationTime = LocalDateTime.parse((bucket \ "CreationDate").text, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        })
      }, { response => s"Could not get a list of all buckets. Response: ${response.status} - $response" })

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

  // Utility functions

  private[s3] def getXml[T](req: => StandaloneWSRequest)(
    f: scala.xml.Elem => Source[T, NotUsed],
    err: StandaloneWSResponse => String)(implicit m: Materializer): Source[T, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(req.withMethod("GET").stream().flatMap { response =>
      if (response.status == 200 || response.status == 206) {

        Future.successful(response.bodyAsSource.mapMaterializedValue(_ => NotUsed.getInstance).
          fold(StringBuilder.newBuilder) {
            _ ++= _.utf8String
          }.
          flatMapConcat { buf =>
            f(scala.xml.XML.loadString(buf.result()))
          })
      } else Future.failed[Source[T, NotUsed]](new IllegalStateException(err(response)))
    }).flatMapConcat(identity)
  }
}

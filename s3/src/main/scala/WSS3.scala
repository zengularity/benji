package com.zengularity.benji.s3

import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import scala.collection.JavaConverters._

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.{ BodyWritable, StandaloneWSRequest, StandaloneWSResponse }
import play.shaded.ahc.io.netty.handler.codec.http.QueryStringDecoder

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

  /**
   * Try to create a S3Config from an URI using the following format:
   * s3:http://accessKey:secretKey@s3.amazonaws.com/?style=[virtualHost|path]
   *
   * {{{
   *   S3("s3:http://accessKey:secretKey@s3.amazonaws.com/?style=virtualHost")
   *   // or
   *   S3(new java.net.URI("s3:https://accessKey:secretKey@s3.amazonaws.com/?style=path"))
   * }}}
   *
   * @param config the config element used by the provider to generate the URI
   * @param provider a typeclass that try to generate an URI from the config element
   * @tparam T the config type to be consumed by the provider typeclass
   * @return Success if the WSS3 was properly created, otherwise Failure
   */
  def apply[T](config: T)(implicit provider: S3URIProvider[T]): Try[WSS3] =
    provider(config).flatMap { builtUri =>
      if (builtUri == null) {
        throw new IllegalArgumentException("URI provider returned a null URI")
      }

      // URI object fails to parse properly with scheme like "s3:http"
      // So we check for "s3" scheme and then recreate an URI without it
      if (builtUri.getScheme != "s3") {
        throw new IllegalArgumentException("Expected URI with scheme containing \"s3:\"")
      }

      val uri = new URI(builtUri.toString.drop(3))

      if (uri.getUserInfo == null) {
        throw new IllegalArgumentException("Expected URI containing accessKey and secretKey")
      }

      val (accessKey, remaining) = uri.getUserInfo.span(_ != ':')
      val secretKey = remaining.drop(1)

      val host = uri.getHost
      val scheme = uri.getScheme

      val params = parseQuery(uri)

      params.get("style") match {
        case Some(Seq("virtualHost")) => Success(virtualHost(accessKey, secretKey, scheme, host))
        case Some(Seq("path")) => Success(apply(accessKey, secretKey, scheme, host))
        case Some(style) => Failure(new IllegalArgumentException(s"Invalid style parameter in URI: $style"))
        case None => Failure(new IllegalArgumentException("Expected style parameter in URI"))
      }
    }

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

  private def parseQuery(uri: URI): Map[String, Seq[String]] =
    new QueryStringDecoder(uri.toString).parameters.asScala.mapValues(_.asScala).toMap
}

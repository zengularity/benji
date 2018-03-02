/*
 * Copyright (C) 2018-2018 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

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

import play.api.libs.ws.{ StandaloneWSRequest, StandaloneWSResponse }
import play.api.libs.ws.ahc.{ AhcWSClientConfig, StandaloneAhcWSClient }
import play.shaded.ahc.io.netty.handler.codec.http.QueryStringDecoder

import com.zengularity.benji.{ Bucket, ObjectStorage, URIProvider }

/**
 * Implementation of the S3 API for Object Storage using Play's WS library.
 *
 * @param requestBuilder the request builder
 * @param requestTimeout the optional timeout for the prepared requests
 * @define contentTypeParam the MIME type of content
 */
class WSS3(
  val transport: StandaloneAhcWSClient,
  requestBuilder: WSRequestBuilder,
  val requestTimeout: Option[Long] = None) extends ObjectStorage { self =>

  import scala.concurrent.duration._

  private[s3] def request(bucketName: Option[String] = None, objectName: Option[String] = None, query: Option[String] = None, requestTimeout: Option[Long] = None): StandaloneWSRequest = {
    val req = requestBuilder(transport, bucketName, objectName, query)

    requestTimeout.fold(req) { t => req.withRequestTimeout(t.milliseconds) }
  }

  def withRequestTimeout(timeout: Long): WSS3 =
    new WSS3(transport, requestBuilder, Some(timeout))

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTServiceGET.html
   */
  object buckets extends self.BucketsRequest {
    def error(response: StandaloneWSResponse): Throwable = new IllegalStateException(s"Could not get a list of all buckets. Response: ${response.status} - ${response.body}")

    def apply()(implicit m: Materializer): Source[Bucket, NotUsed] =
      S3.getXml[Bucket](request(requestTimeout = requestTimeout))({ xml =>
        def buckets = xml \ "Buckets" \ "Bucket"

        Source(buckets.map { bucket =>
          Bucket(
            name = (bucket \ "Name").text,
            creationTime = LocalDateTime.parse((bucket \ "CreationDate").text, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        })
      }, error)

    // TODO: Use pagination
  }

  def bucket(name: String): WSS3BucketRef = new WSS3BucketRef(this, name)
}

/** S3 companion */
object S3 {
  import com.zengularity.benji.LongVal

  /**
   * Returns the S3 client in the path style.
   *
   * @param accessKeyId the user access key
   * @param secretAccessKeyId the user secret key
   * @param scheme the scheme
   * @param host the host name (or IP address)
   * @return A WSS3 instance configured to work with the S3-compatible API of a the server
   */
  def apply(accessKeyId: String, secretAccessKeyId: String, scheme: String, host: String)(implicit ws: StandaloneAhcWSClient): WSS3 = new WSS3(ws, new PathStyleWSRequestBuilder(new SignatureCalculator(accessKeyId, secretAccessKeyId, host), new java.net.URL(s"${scheme}://${host}")))

  /**
   * Returns the S3 client in the virtual host style.
   *
   * @param accessKeyId the user access key
   * @param secretAccessKeyId the user secret key
   * @param scheme the scheme
   * @param host the host name (or IP address)
   * @return A WSS3 instance configured to work with the S3-compatible API of a the server
   */
  def virtualHost(accessKeyId: String, secretAccessKeyId: String, scheme: String, host: String)(implicit ws: StandaloneAhcWSClient): WSS3 = new WSS3(ws, new VirtualHostWSRequestBuilder(new SignatureCalculator(accessKeyId, secretAccessKeyId, host), new java.net.URL(s"${scheme}://${host}")))

  /**
   * Tries to create a S3 client from an URI using the following format:
   * s3:http://accessKey:secretKey@s3.amazonaws.com/?style=[virtualHost|path]
   *
   * The `accessKey` and `secretKey` must not be URI-encoded.
   *
   * {{{
   * S3("s3:http://accessKey:secretKey@s3.amazonaws.com/?style=virtualHost")
   * // or
   * S3(new java.net.URI("s3:https://accessKey:secretKey@s3.amazonaws.com/?style=path"))
   * }}}
   *
   * @param config the config element used by the provider to generate the URI
   * @param provider a typeclass that try to generate an URI from the config element
   * @tparam T the config type to be consumed by the provider typeclass
   * @return Success if the WSS3 was properly created, otherwise Failure
   */
  def apply[T](config: T)(implicit ws: StandaloneAhcWSClient, provider: URIProvider[T]): Try[WSS3] = {
    def fromUri(uri: URI, accessKey: String, secretKey: String): Try[WSS3] = {
      val host = uri.getHost
      val scheme = uri.getScheme
      val params = parseQuery(uri)

      def reqTimeout: Try[Option[Long]] = params.get("requestTimeout") match {
        case Some(Seq(LongVal(timeout))) => Success(Some(timeout))

        case Some(Seq(v)) => Failure[Option[Long]](new IllegalArgumentException(
          s"Invalid request timeout parameter in URI: $v"))

        case Some(ps) => Failure[Option[Long]](new IllegalArgumentException(
          s"Invalid request timeout parameter in URI: $ps"))

        case _ => Success(Option.empty[Long])
      }

      def storage = params.get("style") match {
        case Some(Seq("virtualHost")) =>
          Success(virtualHost(accessKey, secretKey, scheme, host))

        case Some(Seq("path")) =>
          Success(apply(accessKey, secretKey, scheme, host))

        case Some(style) => Failure[WSS3](new IllegalArgumentException(
          s"Invalid style parameter in URI: $style"))

        case _ => Failure[WSS3](new IllegalArgumentException(
          "Expected style parameter in URI"))

      }

      for {
        timeout <- reqTimeout
        s3 <- storage
      } yield timeout.fold(s3)(s3.withRequestTimeout(_))
    }

    provider(config).flatMap { builtUri =>
      if (builtUri == null) {
        Failure[WSS3](new IllegalArgumentException(
          "URI provider returned a null URI"))

      } else if (builtUri.getScheme != "s3") {
        // URI object fails to parse properly with scheme like "s3:http"
        // So we check for "s3" scheme and then recreate an URI without it

        Failure[WSS3](new IllegalArgumentException(
          "Expected URI with scheme containing \"s3:\""))

      } else {
        builtUri.getSchemeSpecificPart match {
          case HttpUrl(scheme, accessKey, secret, raw) =>
            fromUri(new URI(s"$scheme://$raw"), accessKey, secret)

          case uri =>
            Failure[WSS3](new IllegalArgumentException(
              s"Expected URI containing accessKey and secretKey: $uri"))
        }
      }
    }
  }

  def apply(requestBuilder: WSRequestBuilder)(implicit ws: StandaloneAhcWSClient): WSS3 = new WSS3(ws, requestBuilder)

  // Utility functions

  /** Returns a WS client (take care to close it once used). */
  def client(config: AhcWSClientConfig = AhcWSClientConfig())(implicit materializer: Materializer): StandaloneAhcWSClient = StandaloneAhcWSClient(config)

  private[s3] def getXml[T](req: => StandaloneWSRequest)(
    f: scala.xml.Elem => Source[T, NotUsed],
    err: StandaloneWSResponse => Throwable)(implicit m: Materializer): Source[T, NotUsed] = {
    implicit def ec: ExecutionContext = m.executionContext

    Source.fromFuture(req.withMethod("GET").stream().flatMap { response =>
      if (response.status == 200 || response.status == 206) {

        Future.successful(response.bodyAsSource.mapMaterializedValue(_ => NotUsed).
          fold(StringBuilder.newBuilder) {
            _ ++= _.utf8String
          }.
          flatMapConcat { buf =>
            f(scala.xml.XML.loadString(buf.result()))
          })
      } else Future.failed[Source[T, NotUsed]](err(response))
    }).flatMapConcat(identity)
  }

  private def parseQuery(uri: URI): Map[String, Seq[String]] =
    new QueryStringDecoder(uri.toString).parameters.asScala.mapValues(_.asScala).toMap

  private lazy val HttpUrl = "^(http[s]*)://([^:]+):([^@]+)@(.+)$".r
}

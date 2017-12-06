package com.zengularity.benji.s3

import java.net.URL
import java.time.format.DateTimeFormatter
import java.time.{ Instant, ZoneOffset }
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.collection.JavaConverters._
import scala.util.Try

import play.api.libs.ws.WSSignatureCalculator
import play.shaded.ahc.io.netty.handler.codec.http.HttpHeaders
import play.shaded.ahc.org.asynchttpclient.{ Request, RequestBuilderBase }

/** S3 [[http://docs.aws.amazon.com/AmazonS3/latest/dev/VirtualHosting.html request style]]. */
sealed trait RequestStyle
object PathRequest extends RequestStyle
object VirtualHostRequest extends RequestStyle

object RequestStyle {
  def apply(raw: String): RequestStyle = raw match {
    case "virtualhost" => VirtualHostRequest
    case _ => PathRequest
  }
}

/**
 * Computes the signature according access and secret keys,
 * to be used along each S3 requests, in the 'Authorization' header.
 *
 * @param accessKey the S3 access key
 * @param secretKey the S3 secret key
 * @param serverHost the S3 server host (name or IP address)
 * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html and http://ceph.com/docs/master/radosgw/s3/authentication/
 */
class SignatureCalculator(
  accessKey: String,
  secretKey: String,
  serverHost: String) extends WSSignatureCalculator
  with play.shaded.ahc.org.asynchttpclient.SignatureCalculator {

  val logger = org.slf4j.LoggerFactory.getLogger("com.zengularity.s3")

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  override def calculateAndAddSignature(request: Request, requestBuilder: RequestBuilderBase[_]): Unit = {
    @inline def header(name: String): Option[String] = {
      Option(request.getHeaders.get(name))
    }

    val date = header("x-amz-date").orElse(header("Date")).getOrElse(
      RFC1123_DATE_TIME_FORMATTER format Instant.now())
    val style = RequestStyle(header("X-Request-Style").getOrElse("path"))

    requestBuilder.setHeader("Date", date)

    val str = stringToSign(
      request.getMethod, style,
      header("Content-MD5"), header("Content-Type"),
      date, request.getHeaders, serverHost, request.getUrl)

    calculateFor(str).map { signature =>
      requestBuilder.setHeader("Authorization", s"AWS $accessKey:$signature")
    }.recover {
      case e =>
        logger.error("Could not calculate the signature", e)
    }

    ()
  }

  /**
   * If no date header is set,
   * we'll use this format to include the current date time,
   * like 'Thu, 23 Apr 2015 15:49:35 +0000'
   */
  private val RFC1123_DATE_TIME_FORMATTER =
    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z").
      withLocale(java.util.Locale.US).withZone(ZoneOffset.UTC)

  /**
   * Computes the string-to-sign for request authentication.
   * @param serverHost the base host of the S3 server (without the bucket prefix in the virtual host style)
   */
  def stringToSign(httpVerb: String, style: RequestStyle, contentMd5: Option[String], contentType: Option[String], date: String, headers: HttpHeaders, serverHost: String, url: String): String = {
    httpVerb + "\n" +
      contentMd5.getOrElse("") + "\n" +
      contentType.getOrElse("") + s"\n$date\n" +
      canonicalizedAmzHeadersFor(headers) +
      canonicalizedResourceFor(style, url, serverHost)
  }

  /**
   * Calculates the signature for the given string to sign.
   * Use this later on in request headers, like
   * `Authorization: AWS AWSAccessKeyId:Signature`
   * where the signature is calculated in the following way:
   *
   * ```
   * Signature = Base64(HMAC-SHA1(YourSecretAccessKeyID, UTF-8-Encoding-Of(StringToSign)))
   * ```
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  def calculateFor(data: String): Try[String] = Try {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(
      secretKey.getBytes("UTF8"), "HmacSHA1"))

    Base64.getEncoder.encodeToString(mac.doFinal(data.getBytes("UTF8")))
  }

  // Utility methods

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#RESTAuthenticationConstructingCanonicalizedAmzHeaders
   */
  def canonicalizedAmzHeadersFor(allHeaders: HttpHeaders): String = {
    val amzHeaderNames = allHeaders.names.asScala.filter(
      _.toLowerCase.startsWith("x-amz-")).toList.sortBy(_.toLowerCase)

    amzHeaderNames.map { amzHeaderName =>
      amzHeaderName.toLowerCase + ":" +
        allHeaders.getAll(amzHeaderName).asScala.mkString(",") + "\n"
    }.mkString
  }

  def canonicalizedAmzHeadersFor(allHeaders: Map[String, Seq[String]]): String = {
    val amzHeaderNames = allHeaders.keys.filter(
      _.toLowerCase.startsWith("x-amz-")).toList.sortBy(_.toLowerCase)

    amzHeaderNames.map { amzHeaderName =>
      amzHeaderName.toLowerCase + ":" +
        allHeaders(amzHeaderName).mkString(",") + "\n"
    }.mkString
  }

  @inline private def checkPathSlash(url: URL): String =
    if (url.getPath startsWith "/") url.getPath else "/" + url.getPath

  /**
   * For an S3 request URL,
   * like 'https://my-bucket.s3.amazonaws.com/test-folder/file.txt' this
   * will return the resource path as required in the authorization signature.
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheCanonicalizedResourceElement
   */
  def canonicalizedResourceFor(style: RequestStyle, requestUrl: String, host: String): String = {
    val url = new URL(requestUrl) // TODO: Unsafe

    val query = Option(url.getQuery).fold("")(q => s"?$q")
    val path = checkPathSlash(url) + query

    style match {
      case PathRequest => path
      case _ => {
        val bucket = url.getHost.dropRight(host.size + 1)
        if (bucket.isEmpty) path else s"/$bucket$path"
      }
    }
  }
}

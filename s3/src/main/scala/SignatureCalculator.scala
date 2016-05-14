package com.zengularity.s3

import java.net.URL

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import com.ning.http.util.Base64

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import play.api.Logger
import play.api.libs.ws.WSSignatureCalculator

import com.ning.http.client.{ Request, RequestBuilderBase }

import scala.collection.JavaConversions._
import scala.util.Try

/** S3 [[http://docs.aws.amazon.com/AmazonS3/latest/dev/VirtualHosting.html request style]]. */
sealed trait RequestStyle
object PathRequest extends RequestStyle
object VirtualHostRequest extends RequestStyle

object RequestStyle {
  def apply(raw: String): RequestStyle = raw match {
    case "virtualhost" => VirtualHostRequest
    case _             => PathRequest
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
  serverHost: String
) extends WSSignatureCalculator
    with com.ning.http.client.SignatureCalculator {

  val logger = Logger("com.zengularity.s3")
  type HeaderMap = java.util.Map[String, java.util.List[String]]

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  override def calculateAndAddSignature(request: Request, requestBuilder: RequestBuilderBase[_]): Unit = {
    @inline def header(name: String): Option[String] = {
      Option(request.getHeaders.getFirstValue(name))
    }

    val date = header("x-amz-date").orElse(header("Date")).getOrElse(
      RFC1123_DATE_TIME_FORMATTER print DateTime.now()
    )
    val style = RequestStyle(header("X-Request-Style").getOrElse("path"))

    requestBuilder.setHeader("Date", date)

    val str = stringToSign(
      request.getMethod, style,
      header("Content-MD5"), header("Content-Type"),
      date, request.getHeaders, serverHost, request.getUrl
    )

    calculateFor(str).map { signature =>
      requestBuilder.setHeader("Authorization", s"AWS $accessKey:$signature")
    }.recover {
      case e =>
        logger.error("Could not calculate the signature", e)
    }
  }

  /**
   * If no date header is set,
   * we'll use this format to include the current date time,
   * like 'Thu, 23 Apr 2015 15:49:35 +0000'
   */
  private val RFC1123_DATE_TIME_FORMATTER =
    DateTimeFormat.forPattern("EEE, dd MMM yyyy HH:mm:ss Z").
      withLocale(java.util.Locale.US).withZoneUTC()

  /**
   * Computes the string-to-sign for request authentication.
   * @param serverHost the base host of the S3 server (without the bucket prefix in the virtual host style)
   */
  def stringToSign(httpVerb: String, style: RequestStyle, contentMd5: Option[String], contentType: Option[String], date: String, headers: HeaderMap, serverHost: String, url: String): String = {
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
   * ``
   * Signature = Base64(HMAC-SHA1(YourSecretAccessKeyID, UTF-8-Encoding-Of(StringToSign)))
   * ``
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  def calculateFor(data: String): Try[String] = Try {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(new SecretKeySpec(
      secretKey.getBytes("UTF8"), "HmacSHA1"
    ))

    Base64.encode(mac.doFinal(data.getBytes("UTF8")))
  }

  // Utility methods

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#RESTAuthenticationConstructingCanonicalizedAmzHeaders
   */
  def canonicalizedAmzHeadersFor(allHeaders: HeaderMap): String = {
    val amzHeaderNames = allHeaders.keySet().filter(_.toLowerCase.startsWith("x-amz-")).toList.sortBy(_.toLowerCase)
    amzHeaderNames.map({ amzHeaderName =>
      amzHeaderName.toLowerCase + ":" + allHeaders(amzHeaderName).mkString(",") + "\n"
    }).mkString
  }

  def canonicalizedAmzHeadersFor(allHeaders: Map[String, Seq[String]]): String = {
    val amzHeaderNames = allHeaders.keys.filter(
      _.toLowerCase.startsWith("x-amz-")
    ).toList.sortBy(_.toLowerCase)

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
        if (bucket.isEmpty) path else s"/${bucket}$path"
      }
    }
  }
}

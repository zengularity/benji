package com.zengularity.s3

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import com.ning.http.util.Base64

import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat

import play.api.Logger
import play.api.libs.ws.{ WSRequest, WSSignatureCalculator }

import com.ning.http.client.{ Request, RequestBuilderBase }
import com.ning.http.client.FluentCaseInsensitiveStringsMap

import scala.collection.JavaConversions._
import scala.util.Try

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

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  override def calculateAndAddSignature(request: Request, requestBuilder: RequestBuilderBase[_]): Unit = {
    @inline def header(name: String): Option[String] = {
      Option(request.getHeaders.getFirstValue(name))
    }

    val date = header("Date").
      getOrElse(RFC1123_DATE_TIME_FORMATTER print DateTime.now())

    val host = header("Host").getOrElse(serverHost)

    requestBuilder.setHeader("Date", date)

    val stringToSign = request.getMethod + "\n" +
      header("Content-MD5").getOrElse("") + "\n" +
      header("Content-Type").getOrElse("") + "\n" +
      s"$date\n" +
      canonicalizedAmzHeadersFor(request.getHeaders) +
      canonicalizedResourceFor(request.getUrl, host)

    calculateFor(stringToSign).map { signature =>
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
  def canonicalizedAmzHeadersFor(allHeaders: FluentCaseInsensitiveStringsMap): String = {
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

  /**
   * For an S3 request URL,
   * like 'https://my-bucket.s3.amazonaws.com/test-folder/file.txt' this
   * will return the resource path as required in the authorization signature.
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheCanonicalizedResourceElement
   */
  def canonicalizedResourceFor(requestUrl: String, host: String): String = {
    val url = new java.net.URL(requestUrl)

    val checkPathSlash = { (url: java.net.URL) =>
      if (url.getPath.startsWith("/")) url.getPath else "/" + url.getPath
    }

    val path = if (url.getQuery != null && url.getQuery.nonEmpty) {
      checkPathSlash(url) + "?" + url.getQuery
    } else checkPathSlash(url)

    if (isPathStyleRequest(url, host)) path
    else "/" + url.getHost.replaceFirst("." + host, "") + path
  }

  protected def isPathStyleRequest(requestUrl: java.net.URL, hostHeader: String): Boolean = requestUrl.getHost == serverHost || hostHeader.contains(requestUrl.getHost)

}

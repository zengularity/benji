/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import java.net.URL
import java.time.{ Instant, ZoneOffset }
import java.time.format.DateTimeFormatter
import java.util.{ Base64, Locale }
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import scala.util.{ Failure, Success, Try }

import play.api.libs.ws.WSSignatureCalculator
import play.shaded.ahc.io.netty.handler.codec.http.HttpHeaders
import play.shaded.ahc.org.asynchttpclient.{ Request, RequestBuilderBase }

import com.zengularity.benji.Compat.javaConverters._

/**
 * Computes the signature (V1/V2) according access and secret keys,
 * to be used along each S3 requests, in the 'Authorization' header.
 *
 * @param accessKey the S3 access key
 * @param secretKey the S3 secret key
 * @param serverHost the S3 server host (name or IP address)
 * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html and http://ceph.com/docs/master/radosgw/s3/authentication/
 */
private[s3] class SignatureCalculatorV1(
    accessKey: String,
    secretKey: String,
    serverHost: String)
    extends WSSignatureCalculator
    with play.shaded.ahc.org.asynchttpclient.SignatureCalculator {

  val logger = org.slf4j.LoggerFactory.getLogger("com.zengularity.s3")

  private val subResourceParameters = Seq(
    "acl",
    "lifecycle",
    "location",
    "logging",
    "notification",
    "partNumber",
    "policy",
    "requestPayment",
    "torrent",
    "uploadId",
    "uploads",
    "versionId",
    "versioning",
    "versions",
    "website",
    "delete"
  )

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  override def calculateAndAddSignature(
      request: Request,
      requestBuilder: RequestBuilderBase[_]
    ): Unit = {
    @inline def header(name: String): Option[String] = {
      Option(request.getHeaders.get(name))
    }

    val date = header("x-amz-date")
      .orElse(header("Date"))
      .getOrElse(RFC1123_DATE_TIME_FORMATTER format Instant.now())
    val style = RequestStyle(header("X-Request-Style").getOrElse("path"))

    requestBuilder.setHeader("Date", date)

    val str = stringToSign(
      request.getMethod,
      style,
      header("Content-MD5"),
      header("Content-Type"),
      date,
      request.getHeaders,
      serverHost,
      signatureUrl(request)
    )

    logger.trace(s"s3V1StringToSign {\n$str\n}")

    computeSignature(str) match {
      case Success(signature) => {
        requestBuilder.setHeader("Authorization", s"AWS $accessKey:$signature")
        ()
      }

      case Failure(cause) =>
        logger.error("Could not calculate the signature", cause)
    }
  }

  private[s3] def signatureUrl(request: Request): String = {
    val queryParams = request.getQueryParams

    if (queryParams.isEmpty) {
      request.getUri.toBaseUrl
    } else {
      val signatureQueries = queryParams.asScala.collect {
        case p if subResourceParameters.contains(p.getName) =>
          if (p.getValue != null) {
            s"${p.getName}=${p.getValue}"
          } else {
            p.getName
          }
      }
      val baseUrl = request.getUri.toBaseUrl

      if (signatureQueries.isEmpty) {
        baseUrl
      } else {
        s"""$baseUrl?${signatureQueries mkString "&"}"""
      }
    }
  }

  /**
   * If the `Date` header is not already set, the format 'Thu, 31 Jan 2016 13:45:16 +0000'
   * will be applied on the current date time to set a new header value.
   */
  private val RFC1123_DATE_TIME_FORMATTER =
    DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss Z")
      .withLocale(java.util.Locale.US)
      .withZone(ZoneOffset.UTC)

  /**
   * Computes the string-to-sign for request authentication.
   * @param serverHost the base host of the S3 server (without the bucket prefix in the virtual host style)
   */
  def stringToSign(
      httpVerb: String,
      style: RequestStyle,
      contentMd5: Option[String],
      contentType: Option[String],
      date: String,
      headers: HttpHeaders,
      serverHost: String,
      url: String
    ): String = {
    httpVerb + "\n" +
      contentMd5.getOrElse("") + "\n" +
      contentType.getOrElse("") + s"\n$date\n" +
      canonicalizeHeaders(headers) +
      canonicalizeResource(style, url, serverHost)
  }

  /**
   * Computes the authorization signature,
   * based on the pre-computed string-to-sign.
   *
   * The computed signature must be used in the appropriate header:
   * `Authorization: AWS \$awsSecretKey:\$computedSignature`.
   *
   * The computation is performed as bellow:
   *
   * ```
   * computedSignature = base64(HmacSHA1(awsSecretKey, encodeAsUtf8(stringToSign)))
   * ```
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  def computeSignature(data: String): Try[String] = Try {
    val signatureMac = Mac.getInstance("HmacSHA1")

    signatureMac.init(new SecretKeySpec(secretKey.getBytes("UTF8"), "HmacSHA1"))

    Base64.getEncoder.encodeToString(signatureMac doFinal data.getBytes("UTF8"))
  }

  // Utility methods

  /**
   */
  def canonicalizeHeaders(allHeaders: HttpHeaders): String = {
    val amzHeaderNames = allHeaders.names.asScala
      .filter(_.toLowerCase(Locale.US).startsWith("x-amz-"))
      .toList
      .sortBy(_.toLowerCase(Locale.US))

    amzHeaderNames.map { amzHeaderName =>
      amzHeaderName.toLowerCase(Locale.US) + ":" +
        allHeaders.getAll(amzHeaderName).asScala.mkString(",") + "\n"
    }.mkString
  }

  @inline private def checkPathSlash(url: URL): String = {
    val path = url.getPath
    if (path startsWith "/") path else "/" + path
  }

  /**
   * Considering a S3 URL of the form
   * 'https://my-bucket.s3.amazonaws.com/test-folder/file.txt',
   * only the path of the resource (e.g. '/test-folder/file.txt') needed
   * (to compute the signature) will be returned.
   *
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheCanonicalizedResourceElement
   */
  def canonicalizeResource(
      style: RequestStyle,
      requestUrl: String,
      host: String
    ): String = {
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

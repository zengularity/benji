/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import java.time.{ Instant, LocalDateTime, ZoneOffset }
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils

import play.api.libs.ws.WSSignatureCalculator
import play.shaded.ahc.io.netty.handler.codec.http.HttpHeaders
import play.shaded.ahc.org.asynchttpclient.{ Request, RequestBuilderBase }

import com.zengularity.benji.Compat.javaConverters._

/**
 * Computes the signature V4 according access and secret keys,
 * to be used along each S3 requests, in the 'Authorization' header.
 *
 * @param accessKey the S3 access key
 * @param secretKey the S3 secret key
 * @param awsRegion the AWS region (e.g. us-east-1)
 * @param awsService `s3` (except for test purpose)
 * @see https://docs.aws.amazon.com/general/latest/gr/signature-version-4.html
 */
private[s3] final class SignatureCalculatorV4(
    accessKey: String,
    secretKey: String,
    awsRegion: String,
    awsService: String = "s3")
    extends WSSignatureCalculator
    with play.shaded.ahc.org.asynchttpclient.SignatureCalculator {

  val logger = org.slf4j.LoggerFactory.getLogger("com.zengularity.s3")

  def withService(awsService: String): SignatureCalculatorV4 =
    new SignatureCalculatorV4(secretKey, awsRegion, awsService)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/dev/RESTAuthentication.html#ConstructingTheAuthenticationHeader
   */
  override def calculateAndAddSignature(
      request: Request,
      requestBuilder: RequestBuilderBase[_]
    ): Unit = {
    @inline def header(name: String): Option[String] =
      Option(request.getHeaders.get(name))

    val awsDate = header("x-amz-date")
      .orElse(header("Date").map { rfcDate =>
        LocalDateTime
          .parse(rfcDate, RFC1123_DATE_TIME_FORMATTER)
          .format(ISO_8601_FORMATTER)
      })
      .getOrElse(ISO_8601_FORMATTER format Instant.now())

    val cscope = credentialScope(awsDate)

    logger.trace(s"awsDate = $awsDate, credentialScope = $cscope")

    val hashedPayload = "UNSIGNED-PAYLOAD"
    // TODO: https://github.com/aws/aws-sdk-java-v2/blob/fc06af54b94e575f85bbf453b9765ab2e959fccc/services/s3/src/main/java/software/amazon/awssdk/services/s3/AwsS3V4Signer.java#L160

    val (canoReq, signedHeaders) =
      canonicalRequest(request, awsDate, hashedPayload)

    logger.trace(s"awsV4CanonicalRequest {\n$canoReq\n}")

    if (logger.isTraceEnabled) {
      val trace = new StringBuilder("awsV4CanonicalRequestBytes {\n")

      canoReq.getBytes("UTF-8").foreach { byte =>
        trace.append("%02x " format byte)
      }

      logger.trace(s"${trace.toString}\n}")
    }

    val str = stringToSign(canoReq, awsDate, cscope)

    logger.trace(s"awsV4StringToSign {\n$str\n}")

    if (logger.isTraceEnabled) {
      val trace = new StringBuilder("awsV4StringToSignBytes {\n")

      str.getBytes("UTF-8").foreach { byte =>
        trace.append("%02x " format byte)
      }

      logger.trace(s"${trace.toString}\n}")
    }

    val signature = calculateSignature(awsDate, str)

    logger.trace(s"awsV4Signature = $signature")

    val auth = authorizationHeader(cscope, signedHeaders, signature)

    logger.debug(s"authorization = $auth")

    requestBuilder.setHeader("x-amz-date", awsDate)
    requestBuilder.setHeader("x-amz-content-sha256", hashedPayload)
    requestBuilder.setHeader("Authorization", auth)

    ()
  }

  // --- Canonical request
  // See https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html

  def canonicalRequest(
      request: Request,
      awsDate: String,
      hashedPayload: String
    ): (String, String) = {
    val (canoHeaders, signedHeaders) =
      canonicalHeaders(request, awsDate, hashedPayload)

    s"${request.getMethod}\n${canonicalUri(request)}\n${canonicalQueryString(
      request
    )}\n${canoHeaders}\n${signedHeaders}\n${hashedPayload}" -> signedHeaders
  }

  @inline def canonicalUri(request: Request): String = {
    val path = request.getUri.getPath

    if (path == "") {
      "/"
    } else {
      path
    }
  }

  @inline def canonicalQueryString(request: Request): String =
    request.getQueryParams.asScala
      .sortBy(_.getName)
      .map { param =>
        val value = param.getValue

        if (value == null) {
          s"${param.getName}="
        } else {
          val v = value.replaceAll("\\+", "%20") // See QueryParameters
          s"${param.getName}=$v"
        }
      }
      .mkString("&")

  /**
   * Returns the canonical headers, along with the name of the signed headers.
   */
  def canonicalHeaders(
      request: Request,
      awsDate: String,
      hashedPayload: String
    ): (String, String) = {
    val headers: HttpHeaders = request.getHeaders
    val it = {
      val hs = headers.entries.asScala.map { entry =>
        entry.getKey.toLowerCase(Locale.US) -> entry.getValue
      }

      val withEnsuredDateHeader = if (headers.get("x-amz-date") == null) {
        (("x-amz-date" -> awsDate) +: hs)
      } else {
        hs
      }

      if (headers.get("x-amz-content-sha256") == null) {
        (("x-amz-content-sha256" -> hashedPayload) +: withEnsuredDateHeader)
      } else {
        withEnsuredDateHeader
      }
    }

    val signed = List.newBuilder[String]
    val canonical = it
      .sortBy(_._1)
      .map {
        case (name, v) =>
          val value = v.trim.replaceAll("\\s+", " ")

          signed += name

          s"${name}:${value}"
      }
      .mkString("\n")

    s"${canonical}\n" -> signed.result().mkString(";")
  }

  // String-to-sign
  // See: https://docs.aws.amazon.com/general/latest/gr/sigv4-create-string-to-sign.html

  def stringToSign(
      canoRequest: String,
      awsDate: String,
      credentialScope: String
    ): String =
    s"AWS4-HMAC-SHA256\n${awsDate}\n${credentialScope}\n${DigestUtils sha256Hex canoRequest}"

  @inline def credentialScope(awsDate: String): String =
    s"${awsDate take 8}/${awsRegion}/${awsService}/aws4_request"

  // Calculate signature
  // See: https://docs.aws.amazon.com/general/latest/gr/sigv4-calculate-signature.html

  def calculateSignature(awsDate: String, stringToSign: String): String = {
    val hmacSHA256 = Mac.getInstance("HmacSHA256")
    val signingKey = deriveSigningKey(awsDate, hmacSHA256)

    hmacSHA256.init(new SecretKeySpec(signingKey, "HmacSHA256"))

    Hex.encodeHexString(hmacSHA256 doFinal stringToSign.getBytes("UTF8"))
  }

  def deriveSigningKey(awsDate: String, hmacSHA256: Mac): Array[Byte] = {
    @inline def hmac(key: Array[Byte], data: String): Array[Byte] = {
      hmacSHA256.init(new SecretKeySpec(key, "HmacSHA256"))

      hmacSHA256.doFinal(data.getBytes("UTF8"))
    }

    val kDate = hmac(s"AWS4${secretKey}".getBytes("UTF-8"), awsDate.take(8))
    val kRegion = hmac(kDate, awsRegion)
    val kService = hmac(kRegion, awsService)

    hmac(kService, "aws4_request")
  }

  // Prepare the authorization header
  // See: https://docs.aws.amazon.com/general/latest/gr/sigv4-add-signature-to-request.html

  def authorizationHeader(
      credentialScope: String,
      signedHeaders: String,
      signature: String
    ): String =
    s"AWS4-HMAC-SHA256 Credential=${accessKey}/${credentialScope}, SignedHeaders=${signedHeaders}, Signature=${signature}"

  // ---

  /**
   * If the `Date` header is not already set, the format 'Thu, 31 Jan 2016 13:45:16 +0000'
   * will be applied on the current date time to set a new header value.
   */
  private lazy val RFC1123_DATE_TIME_FORMATTER =
    DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss Z")
      .withLocale(java.util.Locale.US)
      .withZone(ZoneOffset.UTC)

  private lazy val ISO_8601_FORMATTER =
    DateTimeFormatter
      .ofPattern("yyyyMMdd'T'HHmmss'Z'")
      .withLocale(java.util.Locale.US)
      .withZone(ZoneOffset.UTC)

}

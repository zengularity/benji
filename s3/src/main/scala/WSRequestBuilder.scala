package com.zengularity.benji.s3

import java.net.URL

import play.api.libs.ws.{ StandaloneWSClient, StandaloneWSRequest }

sealed trait WSRequestBuilder extends ((StandaloneWSClient, Option[String], Option[String], Option[String]) => StandaloneWSRequest) {

  /**
   * @param ws the WS client used to prepared the request
   * @param bucketName the name of the initial bucket
   * @param objectName the name of the initial object
   * @param query a query string representing URL optional parameters
   */
  def apply(ws: StandaloneWSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): StandaloneWSRequest
}

object WSRequestBuilder {
  /**
   * @param ws the WS client used to prepared the request
   * @param calculator the calculator for the request signature
   * @param url the fully qualified URL
   * @param hostHeader the hostname for the `Host` header of the request HTTP
   * @param style the request style (actually `"path"` or `"virtualhost"`)
   */
  private[s3] def build(ws: StandaloneWSClient, calculator: SignatureCalculator, url: String, hostHeader: String, style: String): StandaloneWSRequest =
    ws.url(url).addHttpHeaders(
      "Host" -> hostHeader,
      "X-Request-Style" -> style).sign(calculator)

}

/** Extractor for URL. */
private[s3] object URLInformation {
  /** Extracts (protocol scheme, host with port) from the given url. */
  def unapply(url: URL): Option[(String, String)] = {
    val hostWithPort = if (url.getPort > 0) {
      s"${url.getHost}:${url.getPort}"
    } else url.getHost

    Some(url.getProtocol -> hostWithPort)
  }
}

/**
 * Builder for requests using the path style
 * (e.g. https://s3.amazonaws.com/bucket-name/object?uploads).
 */
final class PathStyleWSRequestBuilder private[s3] (
  calculator: SignatureCalculator, serverUrl: URL) extends WSRequestBuilder {

  def apply(ws: StandaloneWSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): StandaloneWSRequest = {
    val url = new StringBuilder()
    val URLInformation(scheme, host) = serverUrl

    url.append(scheme).append("://").append(host).append('/')

    bucketName.foreach { name =>
      url.append(name)
    }

    objectName.foreach { name =>
      url.append('/').append(name)
    }

    query.foreach { string =>
      url.append('?').append(string)
    }

    WSRequestBuilder.build(
      ws, calculator, url.toString, serverUrl.getHost, "path")
  }
}

/**
 * Builds something like "https://bucket-name.s3.amazonaws.com/object?uploads"
 *
 * This kind of builder is for S3 account where the buckets are virtual hosted
 * (won't work for IP address or server not supporting virtualhost style).
 *
 * This is fine with Amazon S3 services, for others
 * the `PathStyleWSRequestBuilder` is available.
 */
final class VirtualHostWSRequestBuilder private[s3] (
  calculator: SignatureCalculator, serverUrl: URL) extends WSRequestBuilder {

  def apply(ws: StandaloneWSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): StandaloneWSRequest = {
    val url = new StringBuilder()
    val URLInformation(scheme, host) = serverUrl

    url.append(scheme).append("://")

    bucketName.foreach { name =>
      url.append(name).append('.')
    }

    url.append(host).append('/')

    objectName.foreach { name =>
      url.append(name)
    }

    query.foreach { string =>
      url.append('?').append(string)
    }

    val serverHost = bucketName.fold(host) { b => s"$b.$host" }

    WSRequestBuilder.build(
      ws, calculator, url.toString, serverHost, "virtualhost")
  }
}

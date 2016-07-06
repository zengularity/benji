package com.zengularity.s3

import java.net.URL

import play.api.libs.ws.{ WSClient, WSRequest }

sealed trait WSRequestBuilder extends ((WSClient, Option[String], Option[String], Option[String]) => WSRequest) {

  /**
   * @param ws the WS client used to prepared the request
   * @param bucketName the name of the initial bucket
   * @param objectName the name of the initial object
   * @param query a query string representing URL optional parameters
   */
  def apply(ws: WSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): WSRequest
}

object WSRequestBuilder {
  /**
   * @param ws the WS client used to prepared the request
   * @param calculator the calculator for the request signature
   * @param url the fully qualified URL
   * @param hostHeader the hostname for the `Host` header of the request HTTP
   * @param style the request style (actually `"path"` or `"virtualhost"`)
   */
  private[s3] def build(ws: WSClient, calculator: SignatureCalculator, url: String, hostHeader: String, style: String): WSRequest = ws.url(url).withHeaders(
    "Host" -> hostHeader,
    "X-Request-Style" -> style
  ).sign(calculator)

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
class PathStyleWSRequestBuilder private[s3] (
    calculator: SignatureCalculator, serverUrl: URL
) extends WSRequestBuilder {

  def apply(ws: WSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): WSRequest = {
    val url = new StringBuilder()
    val URLInformation(scheme, host) = serverUrl

    url.append(scheme).append("://").append(host).append('/')

    bucketName.foreach { name =>
      url.append(name)
    }

    objectName.foreach { name =>
      url.append('/').append(name)

      query.foreach { string =>
        url.append('?').append(string)
      }
    }

    WSRequestBuilder.build(
      ws, calculator, url.toString, serverUrl.getHost, "path"
    )
  }
}

/**
 * Builds something like "https://bucket-name.s3.amazonaws.com/object?uploads"
 *
 * This only works though, if virtual sub-domains / virtual hosts are enabled
 * for the given server URL. For IP addresses, for example, it will never work,
 * and even in the other cases DNS resolving needs to support that.
 *
 * It certainly works for Amazon S3 services,
 * for the other ones you might be better off using path style requests.
 */
class VirtualHostWSRequestBuilder private[s3] (
    calculator: SignatureCalculator, serverUrl: URL
) extends WSRequestBuilder {

  def apply(ws: WSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): WSRequest = {
    val url = new StringBuilder()
    val URLInformation(scheme, host) = serverUrl

    url.append(scheme).append("://")

    bucketName.foreach { name =>
      url.append(name).append('.')
    }

    url.append(host).append('/')

    objectName.foreach { name =>
      url.append(name)

      query.foreach { string =>
        url.append('?').append(string)
      }
    }

    val serverHost = bucketName.fold(host) { b => s"$b.$host" }

    WSRequestBuilder.build(
      ws, calculator, url.toString, serverHost, "virtualhost"
    )
  }
}
package com.zengularity.s3

import java.net.URL

import play.api.libs.ws.{ WSClient, WSRequest }

/** Information prepared for the request according the [[RequestStyle]]. */
case class PreparedRequest(
  hostHeader: String,
  style: String,
  url: String
)

/**
 * Strategy that takes care of building basic WS requests.
 *
 * Depending on your infrastructure,
 * you might need to use path-style requests (CEPH)
 * or virtual-host style requests (Amazon S3).
 */
abstract class WSRequestBuilder(calculator: SignatureCalculator, serverUrl: URL) {
  def request(bucketName: String = "", objectName: String = "", query: String = "", requestTimeout: Option[Long] = None)(implicit ws: WSClient): WSRequest = {
    val hostWithPort = if (serverUrl.getPort > 0) {
      s"${serverUrl.getHost}:${serverUrl.getPort}"
    } else serverUrl.getHost

    val prepared = prepare(
      serverUrl.getProtocol, hostWithPort, bucketName, objectName, query
    )
    val req = ws.url(prepared.url).withHeaders(
      "Host" -> prepared.hostHeader,
      "X-Request-Style" -> prepared.style
    ).sign(calculator)

    requestTimeout.fold(req)(req.withRequestTimeout(_))
  }

  /** Base builder function, to construct the request URL. */
  protected[s3] def prepare(scheme: String, host: String, bucketName: String, objectName: String, query: String): PreparedRequest
}

/**
 * Builds something like "https://s3.amazonaws.com/bucket-name/object?uploads
 */
class PathStyleWSRequestBuilder(
    calculator: SignatureCalculator, serverUrl: URL
) extends WSRequestBuilder(calculator, serverUrl) {

  protected[s3] def prepare(scheme: String, host: String, bucketName: String, objectName: String, query: String): PreparedRequest = {
    val url = new StringBuilder()

    url.append(scheme).append("://").append(host).append('/')
    url.append(bucketName)

    if (objectName.nonEmpty) {
      url.append('/').append(objectName)

      if (query.nonEmpty) {
        url.append('?').append(query)
      }
    }

    PreparedRequest(serverUrl.getHost, "path", url.toString())
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
class VirtualHostWSRequestBuilder(
    calculator: SignatureCalculator, serverUrl: URL
) extends WSRequestBuilder(calculator, serverUrl) {

  protected[s3] def prepare(scheme: String, host: String, bucketName: String, objectName: String, query: String): PreparedRequest = {
    val url = new StringBuilder()

    url.append(scheme).append("://")

    if (bucketName.nonEmpty) {
      url.append(bucketName).append('.')
    }

    url.append(host).append('/')

    if (objectName.nonEmpty) {
      url.append(objectName)

      if (query.nonEmpty) {
        url.append('?').append(query)
      }
    }

    PreparedRequest(s"$bucketName.$host", "virtualhost", url.toString())
  }
}

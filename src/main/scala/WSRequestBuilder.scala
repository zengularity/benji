package com.zengularity.s3

import java.net.URL

import play.api.libs.ws.{ WSClient, WSRequestHolder }

/**
 * Strategy that takes care of building basic WS requests.
 *
 * Depending on your infrastructure,
 * you might need to use path-style requests (CEPH)
 * or virtual-host style requests (Amazon S3).
 */
abstract class WSRequestBuilder(calculator: SignatureCalculator, serverUrl: URL) {
  def request(bucketName: String = "", objectName: String = "", query: String = "")(implicit ws: WSClient): WSRequestHolder = {
    val hostWithPort = if (serverUrl.getPort > 0) {
      s"${serverUrl.getHost}:${serverUrl.getPort}"
    } else serverUrl.getHost

    ws.url(requestUrl(
      serverUrl.getProtocol, hostWithPort, bucketName, objectName, query
    )).withHeaders("Host" -> serverUrl.getHost).sign(calculator)
  }

  /** Base builder function, to construct the request URL. */
  protected[s3] def requestUrl(scheme: String, host: String, bucketName: String, objectName: String, query: String): String
}

/**
 * Builds something like "https://s3.amazonaws.com/bucket-name/object?uploads
 */
class PathStyleWSRequestBuilder(
    calculator: SignatureCalculator, serverUrl: URL
) extends WSRequestBuilder(calculator, serverUrl) {

  protected[s3] def requestUrl(scheme: String, host: String, bucketName: String, objectName: String, query: String): String = {
    val url = new StringBuilder()

    url.append(scheme).append("://").append(host).append('/')
    url.append(bucketName)

    if (objectName.nonEmpty) {
      url.append('/').append(objectName)

      if (query.nonEmpty) {
        url.append('?').append(query)
      }
    }

    url.toString()
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

  protected[s3] def requestUrl(scheme: String, host: String, bucketName: String, objectName: String, query: String): String = {
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

    url.toString()
  }
}

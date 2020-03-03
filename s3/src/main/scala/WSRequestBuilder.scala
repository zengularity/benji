/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import java.net.URL

import play.api.libs.ws.{
  StandaloneWSClient,
  StandaloneWSRequest,
  WSSignatureCalculator
}

private[s3] sealed trait WSRequestBuilder extends ((StandaloneWSClient, Option[String], Option[String], Option[String]) => StandaloneWSRequest) {

  /**
   * @param ws the WS client used to prepared the request
   * @param bucketName the name of the initial bucket
   * @param objectName the name of the initial object
   * @param query a query string representing URL optional parameters
   */
  def apply(ws: StandaloneWSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): StandaloneWSRequest
}

private[s3] object WSRequestBuilder {
  /**
   * @param ws the WS client used to prepared the request
   * @param calculator the calculator for the request signature
   * @param url the fully qualified URL
   * @param hostHeader the hostname for the `Host` header of the request HTTP
   * @param style the request style (actually `"path"` or `"virtualhost"`)
   */
  private[s3] def build(ws: StandaloneWSClient, calculator: WSSignatureCalculator, url: String, hostHeader: String, style: String): StandaloneWSRequest =
    ws.url(url).addHttpHeaders(
      "Host" -> hostHeader,
      "X-Request-Style" -> style).sign(calculator)

  private[s3] def appendName(
    url: StringBuilder,
    name: String): StringBuilder = {
    name.foreach {
      case '!' =>
        url.append("%21")

      case '#' =>
        url.append("%23")

      case '%' =>
        url.append("%26")

      case '$' =>
        url.append("%24")

      case '&' =>
        url.append("%26")

      case '\'' =>
        url.append("%27")

      case '(' =>
        url.append("%28")

      case ')' =>
        url.append("%29")

      case '*' =>
        url.append("%2A")

      case '+' =>
        url.append("%2B")

      case ',' =>
        url.append("%2C")

      case ':' =>
        url.append("%3A")

      case ';' =>
        url.append("%3B")

      case '=' =>
        url.append("%3D")

      case '?' =>
        url.append("%3F")

      case '@' =>
        url.append("%40")

      case '[' =>
        url.append("%5B")

      case ']' =>
        url.append("%5D")

      case chr =>
        url.append(chr)
    }

    url
  }
}

/** Extractor for URL. */
private[s3] object URLInformation {
  /** Extracts (protocol scheme, host with port) from the given url. */
  def unapply(url: URL): Option[(String, String)] = {
    val hostAndPort = if (url.getPort > 0) {
      s"${url.getHost}:${url.getPort.toString}"
    } else url.getHost

    Some(url.getProtocol -> hostAndPort)
  }
}

/**
 * Builder for requests using the path style
 * (e.g. https://s3.amazonaws.com/bucket-name/object?uploads).
 *
 * @param s3Url the server URL
 */
private[s3] final class PathStyleWSRequestBuilder private[s3] (
  calculator: WSSignatureCalculator, s3Url: URL) extends WSRequestBuilder {

  def apply(ws: StandaloneWSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): StandaloneWSRequest = {
    val url = new StringBuilder()
    val URLInformation(scheme, host) = s3Url

    url.append(scheme).append("://").append(host).append('/')

    bucketName.foreach { name =>
      url.append(name)
    }

    objectName.foreach { name =>
      WSRequestBuilder.appendName(url.append('/'), name)
    }

    query.foreach { string =>
      url.append('?').append(string)
    }

    WSRequestBuilder.build(
      ws, calculator, url.toString, s3Url.getHost, "path")
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
private[s3] final class VirtualHostWSRequestBuilder private[s3] (
  calculator: WSSignatureCalculator, s3Url: URL) extends WSRequestBuilder {

  def apply(ws: StandaloneWSClient, bucketName: Option[String], objectName: Option[String], query: Option[String]): StandaloneWSRequest = {
    val url = new StringBuilder()
    val URLInformation(scheme, host) = s3Url

    url.append(scheme).append("://")

    bucketName.foreach { name =>
      url.append(name).append('.')
    }

    url.append(host).append('/')

    objectName.foreach { name =>
      WSRequestBuilder.appendName(url, name)
    }

    query.foreach { string =>
      url.append('?').append(string)
    }

    val serverHost = bucketName.fold(host) { b => s"$b.$host" }

    WSRequestBuilder.build(
      ws, calculator, url.toString, serverHost, "virtualhost")
  }
}

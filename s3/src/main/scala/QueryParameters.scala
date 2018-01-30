package com.zengularity.benji.s3

import java.net.URLEncoder

private[s3] object QueryParameters {

  def maxParam(maybeMax: Option[Long]): Option[String] = maybeMax.map(max => s"max-keys=$max")

  def tokenParam(token: Option[String]): Option[String] = token.map(tok => s"marker=${URLEncoder.encode(tok, "UTF-8")}")

  val versionParam: Option[String] = Some("versions")

  def prefixParam(prefix: String): Option[String] = Some(s"prefix=${URLEncoder.encode(prefix, "UTF-8")}")

  def buildQuery(queryParams: Option[String]*): Option[String] = {
    val query = queryParams.flatMap(_.toList).mkString("&")
    if (query.isEmpty) None else Some(query)
  }
}

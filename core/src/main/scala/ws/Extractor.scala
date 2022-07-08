/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.ws

import java.util.Base64

import akka.util.ByteString

import play.api.libs.ws.StandaloneWSResponse

/**
 * Extractor to match on successful HTTP response,
 * with OK, PARTIAL_CONTENT or NO_CONTENT status code.
 *
 * {{{
 * import play.api.libs.ws.StandaloneWSResponse
 * import com.zengularity.benji.ws.Successful
 *
 * def foo[T](r: StandaloneWSResponse)(
 *   ifSuc: StandaloneWSResponse => T): Option[T] = r match {
 *   case Successful(resp) => Some(ifSuc(resp))
 *   case _ => None
 * }
 * }}}
 */
object Successful {

  // The S3 REST API only ever returns OK or NO_CONTENT ...
  // which is why I'll only check these two.
  def unapply(response: StandaloneWSResponse): Option[StandaloneWSResponse] = {
    if (
      response.status == 200 ||
      response.status == 204 ||
      response.status == 206
    ) {
      Some(response)
    } else None
  }
}

/**
 * Extractor to match on successful HTTP response with OK status code.
 *
 * {{{
 * import play.api.libs.ws.StandaloneWSResponse
 * import com.zengularity.benji.ws.Ok
 *
 * def ifOk[T](r: StandaloneWSResponse)(
 *   f: StandaloneWSResponse => T): Option[T] = r match {
 *   case Ok(resp) => Some(f(resp))
 *   case _ => None
 * }
 * }}}
 */
object Ok {

  def unapply(response: StandaloneWSResponse): Option[StandaloneWSResponse] =
    if (response.status == 200) Some(response) else None
}

/** MD5 checksum utility. */
object ContentMD5 extends (ByteString => String) {

  import org.apache.commons.codec.digest.DigestUtils

  //    akka.util.Helpers.base
  /** Returns the MD5 checksum for the given bytes. */
  def apply(content: ByteString): String =
    Base64.getEncoder.encodeToString(DigestUtils.md5(content.toArray))
}

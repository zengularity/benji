package com.zengularity

import akka.util.ByteString

import play.api.http.Status
import play.api.libs.ws.{ WSResponse, WSResponseHeaders }

package object ws {
  /**
   * Extractor to match on successful HTTP response,
   * with OK, PARTIAL_CONTENT or NO_CONTENT status code.
   *
   * {{{
   * import play.api.libs.ws.{ WSResponse, WSResponseHeaders }
   *
   * def foo[T](r: WSResponse)(ifSuc: WSResponse => T): Option[T] = r match {
   *   case Successful(resp) => Some(ifSuc(res))
   *   case _ => None
   * }
   * }}}
   */
  object Successful {
    // The S3 REST API only ever returns OK or NO_CONTENT ...
    // which is why I'll only check these two.
    def unapply(response: WSResponse): Option[WSResponse] = {
      if (response.status == Status.OK ||
        response.status == Status.PARTIAL_CONTENT ||
        response.status == Status.NO_CONTENT) {
        Some(response)
      } else None
    }

    def unapply(headers: WSResponseHeaders): Option[WSResponseHeaders] = {
      if (headers.status == Status.OK ||
        headers.status == Status.PARTIAL_CONTENT ||
        headers.status == Status.NO_CONTENT) {
        Some(headers)
      } else None
    }
  }

  /**
   * Extractor to match on successful HTTP response with OK status code.
   *
   * {{{
   * import play.api.libs.ws.{ WSResponse, WSResponseHeaders }
   *
   * def ifOk[T](r: WSResponse)(f: WSResponse => T): Option[T] = r match {
   *   case OK(resp) => Some(f(res))
   *   case _ => None
   * }
   * }}}
   */
  object Ok {
    def unapply(response: WSResponse): Option[WSResponse] =
      if (response.status == Status.OK) Some(response) else None
  }

  /** MD5 checksum utility. */
  object ContentMD5 extends (ByteString => String) {
    import org.apache.commons.codec.digest.DigestUtils

    /** Returns the MD5 checksum for the given bytes. */
    def apply(content: ByteString): String =
      org.asynchttpclient.util.Base64.encode(DigestUtils.md5(content.toArray))
  }
}

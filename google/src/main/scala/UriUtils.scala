/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import java.io.ByteArrayOutputStream
import java.util

private[google] object UriUtils {

  /*
    source: https://github.com/playframework/playframework/blob/master/framework/src/play/src/main/scala/play/utils/UriEncoding.scala
   */
  def encodePathSegment(s: String, inputCharset: String): String = {
    val in = s.getBytes(inputCharset)
    val out = new ByteArrayOutputStream()
    for (b <- in) {
      val allowed = segmentChars.get(b & 0xff)
      if (allowed) {
        out.write(b.toInt)
      } else {
        out.write('%')
        out.write(upperHex((b >> 4) & 0xf))
        out.write(upperHex(b & 0xf))
      }
    }
    out.toString("US-ASCII")
  }

  // ---

  // RFC 3986, 3.3. Path
  // segment       = *pchar
  // segment-nz    = 1*pchar
  // segment-nz-nc = 1*( unreserved / pct-encoded / sub-delims / "@" )
  //               ; non-zero-length segment without any colon ":"
  /** The set of ASCII character codes that are allowed in a URI path segment. */
  private val segmentChars: util.BitSet = membershipTable(pchar)

  /** The characters allowed in a path segment; defined in RFC 3986 */
  private def pchar: Seq[Char] = {
    // RFC 3986, 2.3. Unreserved Characters
    // unreserved  = ALPHA / DIGIT / "-" / "." / "_" / "~"
    val alphaDigit =
      for (
        (min, max) <- Seq(('a', 'z'), ('A', 'Z'), ('0', '9')); c <- min to max
      ) yield c
    val unreserved = alphaDigit ++ Seq('-', '.', '_', '~')

    // RFC 3986, 2.2. Reserved Characters
    // sub-delims  = "!" / "$" / "&" / "'" / "(" / ")"
    //             / "*" / "+" / "," / ";" / "="
    val subDelims = Seq('!', '$', '&', '\'', '(', ')', '*', '+', ',', ';', '=')

    // RFC 3986, 3.3. Path
    // pchar         = unreserved / pct-encoded / sub-delims / ":" / "@"
    unreserved ++ subDelims ++ Seq(':', '@')
  }

  /** Create a BitSet to act as a membership lookup table for the given characters. */
  private def membershipTable(chars: Seq[Char]): util.BitSet = {
    val bits = new util.BitSet(256)
    for (c <- chars) { bits.set(c.toInt) }
    bits
  }

  /**
   * Given a number from 0 to 16, return the ASCII character code corresponding
   * to its uppercase hexadecimal representation.
   */
  private def upperHex(x: Int): Int = {
    // Assume 0 <= x < 16
    if (x < 10) (x + '0') else (x - 10 + 'A')
  }
}

/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import java.net.URL

/** Extractor for URL. */
private[s3] object URLInformation {

  /** Extracts (protocol scheme, host with port) from the given url. */
  def unapply(url: URL): (String, String) = {
    val hostAndPort = if (url.getPort > 0) {
      s"${url.getHost}:${url.getPort.toString}"
    } else url.getHost

    url.getProtocol -> hostAndPort
  }
}

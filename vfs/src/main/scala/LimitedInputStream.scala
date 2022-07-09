/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.vfs

import java.io.{ BufferedInputStream, InputStream }

/**
 * @param limit the maximum number of bytes that can be read
 */
private[vfs] class LimitedInputStream(
    underlying: InputStream,
    limit: Int,
    bufferSize: Int = 8192)
    extends BufferedInputStream(underlying, bufferSize) {

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private var index = 0

  override def read(): Int = {
    if (index == limit) -1
    else {
      val res = super.read()
      index = index + 1
      res
    }
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val n = {
      if ((index + len) >= limit) limit - index
      else len
    }

    if (n <= 0) -1
    else {
      val r = super.read(b, off, n)

      index = index + r

      r
    }
  }
}

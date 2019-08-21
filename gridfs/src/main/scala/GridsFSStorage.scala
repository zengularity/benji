/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import com.zengularity.benji.{ Bucket, ObjectStorage }

class GridFSStorage(val transport: GridFSTransport, val requestTimeout: Option[Long]) extends ObjectStorage { self =>

  def withRequestTimeout(timeout: Long) =
    new GridFSStorage(transport, Some(timeout))

  def bucket(name: String) = new GridFSBucketRef(this, name)

  object buckets extends self.BucketsRequest {
    def apply()(implicit m: Materializer): Source[Bucket, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext
      val gridfs = transport.gridfsdb
    }
  }
}

object GridFSStorage {
  /**
   * Returns a client for GridsFS Object Storage.
   *
   * @param transport the GridFS transport
   * @param requestTimeout the optional timeout for the prepared requests (none by default)
   */
  def apply(transport: GridFSTransport, requestTimeout: Option[Long] = None): GridFSStorage = new GridFSStorage(transport, requestTimeout)
}
/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import scala.concurrent.ExecutionContext

import akka.NotUsed

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ Bucket, BucketRef, ObjectStorage }

/**
 * GridFS storage backend for Benji.
 *
 * @param transport the GridFS transport
 */
final class GridFSStorage(val transport: GridFSTransport)
    extends ObjectStorage {

  def bucket(name: String): BucketRef =
    new GridFSBucketRef(transport, name)

  def versioning: None.type = None

  def withRequestTimeout(timeout: Long): ObjectStorage = this

  object buckets extends BucketsRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Source[Bucket, NotUsed] = {
      implicit val ec: ExecutionContext = m.executionContext

      Source
        .fromFuture(transport.getDatabase)
        .flatMapConcat(getBucketNames)
        .map { name => Bucket(name, java.time.LocalDateTime.now()) }
    }
  }

  private def getBucketNames(
      db: reactivemongo.api.DB
    )(implicit
      ec: ExecutionContext
    ): Source[String, NotUsed] =
    Source.fromFuture(db.collectionNames).flatMapConcat { names =>
      // Filter out system and GridFS internal collections
      val bucketNames = names.filter { name =>
        !name.startsWith("system.") && !name.endsWith(".files") && !name
          .endsWith(".chunks")
      }

      Source(bucketNames)
    }
}

object GridFSStorage {

  def apply(transport: GridFSTransport): GridFSStorage =
    new GridFSStorage(transport)
}

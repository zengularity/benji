/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */
package com.zengularity.benji.gridfs

import scala.concurrent.{ ExecutionContext, Future }

import com.zengularity.benji.{ BucketRef, BucketVersioning }

final class GridFSBucketRef private[gridfs] (
  storage: GridFSStorage,
  val name: String) extends BucketRef with BucketVersioning {

  def exists(implicit ec: ExecutionContext): Future[Boolean] = {
    val filesAndChunksStats = for {
      gridfs <- storage.transport.gridfs(name)
      filesStats <- gridfs.files.stats
      chunksStats <- gridfs.chunks.stats
    } yield (filesStats, chunksStats)

    filesAndChunksStats.transform {
      case Success(_) => Success(true)
      case Failure(_) => Success(false)
    }
  }

}
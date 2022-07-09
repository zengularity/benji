/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.vfs

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import org.apache.commons.vfs2.{ FileName, FileType, FileTypeSelector }

import akka.NotUsed

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ Bucket, ObjectStorage }

/**
 * VFS Implementation for Object Storage.
 *
 * @param requestTimeout the optional timeout for the prepared requests
 */
class VFSStorage(
    val transport: VFSTransport,
    val requestTimeout: Option[Long])
    extends ObjectStorage { self =>

  def withRequestTimeout(timeout: Long) =
    new VFSStorage(transport, Some(timeout))

  def bucket(name: String) = new VFSBucketRef(this, name)

  object buckets extends self.BucketsRequest {
    private val selector = new FileTypeSelector(FileType.FOLDER)
    private val rootBaseFile = transport.fsManager.getBaseFile

    @com.github.ghik.silencer.silent(".*fromFuture.*")
    def apply()(implicit m: Materializer): Source[Bucket, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source
        .fromFuture(Future {
          lazy val root = transport.fsManager.resolveFile(rootBaseFile.getURL)
          lazy val items = Option(root.findFiles(selector))

          Source.fromIterator[Bucket] { () =>
            items match {
              case Some(itm) if itm.nonEmpty =>
                itm
                  .filter(
                    _.getName.getBaseName != rootBaseFile.getName.getBaseName
                  )
                  .map { b =>
                    Bucket(
                      b.getName.getPathDecoded.stripPrefix(
                        s"${rootBaseFile.getName.getPath}${FileName.SEPARATOR}"
                      ),
                      LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(b.getContent.getLastModifiedTime),
                        ZoneOffset.UTC
                      )
                    )
                  }
                  .iterator
              case _ => Iterator.empty
            }
          }
        })
        .flatMapMerge(1, identity)
    }

    // TODO: Use pagination
  }
}

/** VFS factory. */
object VFSStorage {

  /**
   * Returns a client for VFS Object Storage.
   *
   * @param transport the VFS transport
   * @param requestTimeout the optional timeout for the prepared requests (none by default)
   */
  def apply(
      transport: VFSTransport,
      requestTimeout: Option[Long] = None
    ): VFSStorage = new VFSStorage(transport, requestTimeout)
}

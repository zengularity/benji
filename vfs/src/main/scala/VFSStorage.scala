package com.zengularity.benji.vfs

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import org.apache.commons.vfs2.{ FileName, FileType, FileTypeSelector }

import com.zengularity.benji.{ Bucket, ObjectStorage }

/**
 * VFS Implementation for Object Storage.
 *
 * @param requestTimeout the optional timeout for the prepared requests
 */
class VFSStorage(
  val transport: VFSTransport,
  val requestTimeout: Option[Long]) extends ObjectStorage { self =>

  def withRequestTimeout(timeout: Long) =
    new VFSStorage(transport, Some(timeout))

  def bucket(name: String) = new VFSBucketRef(this, name)

  object buckets extends self.BucketsRequest {
    private val selector = new FileTypeSelector(FileType.FOLDER)

    def apply()(implicit m: Materializer): Source[Bucket, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        lazy val root = transport.fsManager.resolveFile(FileName.ROOT_PATH)
        lazy val items = root.findFiles(selector)

        Source.fromIterator[Bucket] { () =>
          if (items.isEmpty) Iterator.empty
          else items.filter(!_.getName.getBaseName.isEmpty).map { b =>
            Bucket(b.getName.getBaseName, LocalDateTime.ofInstant(
              Instant.ofEpochMilli(b.getContent.getLastModifiedTime), ZoneOffset.UTC))
          }.iterator
        }
      }).flatMapMerge(1, identity)
    }

    // TODO: Use pagination
  }
}

/** VFS factory. */
object VFSStorage {
  /**
   * Returns a client for VFS Object Storage.
   *
   * @param requestTimeout the optional timeout for the prepared requests (none by default)
   */
  def apply(transport: VFSTransport, requestTimeout: Option[Long] = None): VFSStorage = new VFSStorage(transport, requestTimeout)
}

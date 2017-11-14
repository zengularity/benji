package com.zengularity.vfs

import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import org.apache.commons.vfs2.{
  FileName,
  FileType,
  FileTypeSelector
}

import com.zengularity.storage.{ Bucket, ObjectStorage }

/**
 * VFS Implementation for Object Storage.
 *
 * @param requestTimeout the optional timeout for the prepared requests
 */
class VFSStorage(val requestTimeout: Option[Long])
  extends ObjectStorage[VFSStorage] { self =>

  type Pack = VFSStoragePack.type
  type ObjectRef = VFSObjectRef

  def withRequestTimeout(timeout: Long) = new VFSStorage(Some(timeout))

  def bucket(name: String) = new VFSBucketRef(this, name)

  object buckets extends self.BucketsRequest {
    private val selector = new FileTypeSelector(FileType.FOLDER)

    def apply()(implicit m: Materializer, t: VFSTransport): Source[Bucket, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        lazy val root = t.fsManager.resolveFile(FileName.ROOT_PATH)
        lazy val items = root.findFiles(selector)

        Source.fromIterator[Bucket] { () =>
          if (items.isEmpty) Iterator.empty
          else items.filter(!_.getName.getBaseName.isEmpty).map { b =>
            Bucket(b.getName.getBaseName, new DateTime(
              b.getContent.getLastModifiedTime))
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
  def apply(requestTimeout: Option[Long] = None): VFSStorage = new VFSStorage(requestTimeout)
}

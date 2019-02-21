/*
 * Copyright (C) 2018-2018 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.vfs

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import org.apache.commons.vfs2.{ FileName, FileType }

import com.zengularity.benji.{ BucketRef, BucketVersioning, Bytes, Object }
import com.zengularity.benji.exception.{ BucketNotFoundException, BucketAlreadyExistsException, BucketNotEmptyException }

final class VFSBucketRef private[vfs] (
  storage: VFSStorage,
  val name: String) extends BucketRef { ref =>

  @inline private def logger = storage.logger

  def objects: ref.ListRequest = new VFSListRequest(None)

  private final class VFSListRequest(
    prefix: Option[String]) extends ref.ListRequest {

    private val rootPath = s"${storage.transport.fsManager.getBaseFile.getName.getPath}${FileName.SEPARATOR}$name${FileName.SEPARATOR}"

    private val selector =
      new BenjiFileSelector(dir.getName, FileType.FILE, prefix)

    @SuppressWarnings(Array("org.wartremover.warts.Throw"))
    def apply()(implicit m: Materializer): Source[Object, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        lazy val items = Option(dir.findFiles(selector))

        Source.fromIterator[Object] { () =>
          items match {
            case Some(itm) if itm.nonEmpty =>
              itm.map { o =>
                val content = o.getContent
                Object(
                  o.getName.getPath.stripPrefix(rootPath),
                  Bytes(content.getSize),
                  LocalDateTime.ofInstant(Instant.ofEpochMilli(content.getLastModifiedTime), ZoneOffset.UTC))

              }.iterator
            case Some(_) =>
              Iterator.empty
            case None =>
              throw BucketNotFoundException(name)
          }
        }
      }).flatMapMerge(1, identity)
    }

    def withBatchSize(max: Long) = {
      logger.warn("For VFS storage there is no need for batch size")
      this
    }

    def withPrefix(prefix: String) = new VFSListRequest(Some(prefix))
  }

  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    Future(dir.exists)

  def create(failsIfExists: Boolean = false)(implicit ec: ExecutionContext): Future[Unit] = {
    val before = if (failsIfExists) {
      exists.flatMap {
        case true => Future.failed[Unit](BucketAlreadyExistsException(name))
        case false => Future.successful({})
      }
    } else {
      Future.successful({})
    }
    before.map(_ => dir.createFolder())
  }

  private def emptyBucket()(implicit m: Materializer): Future[Unit] = {
    implicit val ec: ExecutionContext = m.executionContext

    // despite what the deleteAll documentation says, deleteAll don't delete the folder itself
    Future { dir.deleteAll(); () }
  }

  def delete: DeleteRequest = VFSDeleteRequest()

  def obj(objectName: String): VFSObjectRef =
    new VFSObjectRef(storage, name, objectName)

  @inline private def dir = storage.transport.fsManager.resolveFile(name)

  def versioning: Option[BucketVersioning] = None

  override lazy val toString = s"VFSBucketRef($name)"

  override def equals(that: Any): Boolean = that match {
    case other: VFSBucketRef =>
      other.name == this.name

    case _ => false
  }

  override def hashCode: Int = name.hashCode

  // ---

  private case class VFSDeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    private def delete()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      Future { dir.delete() }.flatMap { successful =>
        if (successful) {
          Future.unit
        } else {
          Future { dir.exists() }.flatMap {
            case true => Future.failed[Unit](BucketNotEmptyException(ref))
            case false if ignoreExists => Future.unit
            case false => Future.failed[Unit](BucketNotFoundException(ref))
          }
        }
      }
    }

    def apply()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      if (isRecursive) emptyBucket().flatMap(_ => delete())
      else delete()
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)

    def recursive: DeleteRequest = this.copy(isRecursive = true)
  }
}

package com.zengularity.benji.vfs

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import org.apache.commons.vfs2.{ FileName, FileType, FileTypeSelector }

import com.zengularity.benji.{ BucketRef, BucketVersioning, Bytes, Object }

final class VFSBucketRef private[vfs] (
  val storage: VFSStorage,
  val name: String) extends BucketRef { ref =>

  @inline private def logger = storage.logger

  object objects extends ref.ListRequest {
    private val rootPath = s"${storage.transport.fsManager.getBaseFile.getName.getPath}${FileName.SEPARATOR}$name${FileName.SEPARATOR}"
    private val selector = new FileTypeSelector(FileType.FILE)

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
            case _ => Iterator.empty
          }
        }
      }).flatMapMerge(1, identity)
    }

    def withBatchSize(max: Long) = {
      logger.warn("For VFS storage there is no need for batch size")
      this
    }
  }

  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    Future(dir.exists)

  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = for {
    before <- {
      if (checkBefore) exists.map(Some(_))
      else Future.successful(Option.empty[Boolean])
    }
    _ <- Future(dir.createFolder())
  } yield before.map(!_).getOrElse(true)

  private def emptyBucket()(implicit m: Materializer): Future[Unit] = {
    implicit val ec: ExecutionContext = m.executionContext

    // despite what the deleteAll documentation says, deleteAll don't delete the folder itself
    Future { dir.deleteAll(); () }
  }

  private case class VFSDeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    private def delete()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      Future { dir.delete() }.flatMap { successful =>
        if (ignoreExists || successful) {
          Future.unit
        } else {
          Future.failed[Unit](new IllegalStateException(
            "Could not delete bucket"))
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

  def delete: DeleteRequest = VFSDeleteRequest()

  def obj(objectName: String): VFSObjectRef =
    new VFSObjectRef(storage, name, objectName)

  @inline private def dir = storage.transport.fsManager.resolveFile(name)

  override lazy val toString = s"VFSBucketRef($name)"

  def versioning: Option[BucketVersioning] = None
}

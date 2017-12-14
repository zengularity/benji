package com.zengularity.benji.vfs

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ BucketRef, Bytes, Object }

final class VFSBucketRef private[vfs] (
  val storage: VFSStorage,
  val name: String) extends BucketRef[VFSStorage] { ref =>
  object objects extends ref.ListRequest {
    def apply()(implicit m: Materializer, t: VFSTransport): Source[Object, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        lazy val items = dir.getChildren

        Source.fromIterator[Object] { () =>
          if (items.isEmpty) Iterator.empty else items.map { o =>
            val content = o.getContent

            Object(
              o.getName.getBaseName,
              Bytes(content.getSize),
              LocalDateTime.ofInstant(Instant.ofEpochMilli(content.getLastModifiedTime), ZoneOffset.UTC))

          }.iterator
        }
      }).flatMapMerge(1, identity)
    }

    // TODO: Use pagination
  }

  def exists(implicit ec: ExecutionContext, t: VFSTransport): Future[Boolean] =
    Future(dir.exists)

  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext, tr: VFSTransport): Future[Boolean] = for {
    before <- {
      if (checkBefore) exists.map(Some(_))
      else Future.successful(Option.empty[Boolean])
    }
    _ <- Future(dir.createFolder())
  } yield before.map(!_).getOrElse(true)

  private def emptyBucket()(implicit m: Materializer, tr: VFSTransport): Future[Unit] = {
    implicit val ec = m.executionContext
    // despite what the deleteAll documentation says, deleteAll don't delete the folder itself
    Future { dir.deleteAll(); () }
  }

  def delete()(implicit m: Materializer, tr: VFSTransport): Future[Unit] = {
    implicit val ec = m.executionContext
    Future { dir.delete() }.flatMap(successful =>
      if (successful) Future.unit
      else Future.failed(new IllegalStateException("Could not delete bucket")))
  }

  def delete(recursive: Boolean)(implicit m: Materializer, tr: Transport): Future[Unit] = {
    implicit val ec = m.executionContext
    if (recursive) emptyBucket().flatMap(_ => delete())
    else delete()
  }

  def obj(objectName: String): VFSObjectRef =
    new VFSObjectRef(storage, name, objectName)

  @inline private def dir(implicit t: VFSTransport) =
    t.fsManager.resolveFile(name)

  override lazy val toString = s"VFSBucketRef($name)"
}

package com.zengularity.benji.vfs

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Sink, Source, StreamConverters }
import akka.util.ByteString

import org.apache.commons.vfs2.{
  FileName,
  FileNotFoundException,
  FileType,
  FileTypeSelector
}

import play.api.libs.ws.BodyWritable

import com.zengularity.benji.{ ByteRange, Bytes, Chunk, ObjectRef, Streams }

final class VFSObjectRef private[vfs] (
  val storage: VFSStorage,
  val bucket: String,
  val name: String) extends ObjectRef { ref =>

  val defaultThreshold = VFSObjectRef.defaultThreshold
  @inline private def transport = storage.transport

  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    Future(file.exists)

  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = Future.failed(new RuntimeException("TODO"))

  val get = new VFSGetRequest()

  final class VFSGetRequest private[vfs] () extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        lazy val f = file

        val st = f.getContent.getInputStream

        range.fold[java.io.InputStream](st) { r =>
          if (st.skip(r.start) != r.start) {
            throw new IllegalStateException(
              s"fails to position at offset: ${r.start}")
          } else {
            val sz = (r.end - r.start + 1).toInt

            new LimitedInputStream(st, if (sz < 0) 0 else sz)
          }
        }
      }.map(in => StreamConverters.fromInputStream(() => in)).recoverWith {
        case reason: FileNotFoundException => Future.failed[Source[ByteString, NotUsed]](new IllegalStateException(s"Could not get the contents of the object $name in the bucket $bucket. Response: 404 - ${reason.getMessage}"))

      }).flatMapMerge(1, identity)
    }
  }

  final class RESTPutRequest[E, A] private[vfs] ()
    extends ref.PutRequest[E, A] {

    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None, metadata: Map[String, String])(f: (A, Chunk) => Future[A])(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[A]] = {
      implicit def ec: ExecutionContext = m.executionContext

      lazy val of = file
      lazy val st = of.getContent.getOutputStream
      @volatile var closed = false

      Streams.chunker[E].via(Streams.consumeAtMost(threshold)).via(
        Flow.apply[Chunk].foldAsync[A](z) { (a, chunk) =>
          Future[Chunk] {
            st.write(chunk.data.toArray)
            st.flush()

            chunk
          }.flatMap(f(a, _))
        }).map { a =>
          of.close()
          closed = true
          a
        }.recoverWithRetries(3, {
          case reason if !closed =>
            reason.printStackTrace()
            of.close()
            Source.failed[A](reason)

        }).toMat(Sink.head[A]) { (_, mat) => mat }
    }
  }

  def put[E, A] = new RESTPutRequest[E, A]()

  private case class VFSDeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      Future { file.delete() }.flatMap(successful =>
        if (ignoreExists || successful) Future.unit
        else Future.failed(new IllegalArgumentException(s"Could not delete $bucket/$name: doesn't exist")))
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  def delete: DeleteRequest = VFSDeleteRequest()

  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext): Future[Unit] = {
    def target = transport.fsManager.resolveFile(
      s"$targetBucketName${FileName.SEPARATOR}$targetObjectName")

    lazy val targetObj = storage.bucket(targetBucketName).obj(targetObjectName)

    for {
      _ <- {
        if (!preventOverwrite) Future.successful({})
        else targetObj.exists.flatMap {
          case true => Future.failed[Unit](new IllegalStateException(
            s"Could not move $bucket/$name: target $targetBucketName/$targetObjectName already exists"))

          case _ => Future.successful({})
        }
      }
      _ <- Future(file.moveTo(target))
    } yield ()
  }

  private val copySelector = new FileTypeSelector(FileType.FILE)

  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext): Future[Unit] = {
    def target = transport.fsManager.resolveFile(
      s"$targetBucketName${FileName.SEPARATOR}$targetObjectName")

    Future(target.copyFrom(file, copySelector))
  }

  // Utility methods
  @inline private def file =
    transport.fsManager.resolveFile(s"$bucket${FileName.SEPARATOR}$name")

  override lazy val toString = s"VFSObjectRef($bucket, $name)"
}

object VFSObjectRef {
  def defaultThreshold = Bytes(8192L)
}

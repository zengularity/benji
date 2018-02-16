package com.zengularity.benji.vfs

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

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
import play.api.libs.json.Json
import play.api.libs.ws.BodyWritable

import com.zengularity.benji.exception.{ ObjectNotFoundException, BucketNotFoundException }
import com.zengularity.benji.{
  ByteRange,
  Bytes,
  Chunk,
  ObjectRef,
  Streams,
  ObjectVersioning
}

final class VFSObjectRef private[vfs] (
  val storage: VFSStorage,
  val bucket: String,
  val name: String) extends ObjectRef { ref =>

  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)
  val defaultThreshold = VFSObjectRef.defaultThreshold
  @inline private def transport = storage.transport

  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    Future(file.exists)

  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = {
    Future {
      metadataFile.exists()
    }.flatMap { exists =>
      if (exists) {
        val inputStream = metadataFile.getContent.getInputStream
        Future.fromTry {
          Try {
            val json = Json.parse(inputStream)
            json.as[Map[String, String]].mapValues(str => Seq(str))
          }
        }.andThen {
          case _ =>
            try {
              inputStream.close()
            } catch {
              case NonFatal(err) => logger.warn(s"Fails to close inputstream", err)
            }
        }
      } else {
        Future { file.exists() }.flatMap {
          case true => Future.successful(Map.empty[String, Seq[String]])
          case false => Future.failed[Map[String, Seq[String]]](ObjectNotFoundException(ref))
        }
      }
    }
  }

  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = headers()

  val get = new VFSGetRequest()

  final class VFSGetRequest private[vfs] () extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      @SuppressWarnings(Array("org.wartremover.warts.Throw"))
      def in = Future {
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
      }

      Source.fromFuture(in.map(s => StreamConverters.fromInputStream(() => s)).
        recoverWith {
          case reason: FileNotFoundException =>
            logger.info(s"Could not get the contents of the object $name in the bucket $bucket : $reason")
            Future.failed[Source[ByteString, NotUsed]](ObjectNotFoundException(ref))

        }).flatMapMerge(1, identity)
    }
  }

  final class RESTPutRequest[E, A] private[vfs] ()
    extends ref.PutRequest[E, A] {

    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None, metadata: Map[String, String])(f: (A, Chunk) => Future[A])(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[A]] = {
      implicit def ec: ExecutionContext = m.executionContext

      def writeMetadata(): Try[Unit] = {
        val json = Json.toJson(metadata)
        val jsonBytes = Json.toBytes(json)
        Try {
          metadataFile.getContent.getOutputStream
        }.flatMap { out =>
          try {
            out.write(jsonBytes)
            out.flush()
            Success(())
          } catch {
            case NonFatal(err) =>
              logger.warn(s"Cannot write metadata", err)
              Failure[Unit](err)
          } finally {
            try {
              out.close()
            } catch {
              case NonFatal(err) => logger.warn(s"Fails to close outputstream, ${err.getMessage}", err)
            }
          }
        }
      }

      def upload: Flow[E, A, NotUsed] = {
        lazy val of = file
        lazy val st = of.getContent.getOutputStream

        @SuppressWarnings(Array("org.wartremover.warts.Var" /* local, volatile*/ ))
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
              of.close()
              Source.failed[A](reason)

          })
      }

      val flow = Flow[E].flatMapConcat { entry =>
        Source.fromFuture {
          storage.bucket(bucket).exists.flatMap { exists =>
            if (exists) Future.successful(entry)
            else Future.failed[E](BucketNotFoundException(bucket))
          }
        }
      }.via(upload).map { current =>
        writeMetadata()
        current
      }

      flow.toMat(Sink.head[A]) { (_, mat) => mat }
    }
  }

  def put[E, A] = new RESTPutRequest[E, A]()

  private case class VFSDeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      Future {
        metadataFile.delete()
        file.delete()
      }.flatMap { successful =>
        if (ignoreExists || successful) {
          Future.unit
        } else {
          Future.failed[Unit](ObjectNotFoundException(ref))
        }
      }
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  def delete: DeleteRequest = VFSDeleteRequest()

  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext): Future[Unit] = {
    def target = transport.fsManager.resolveFile(
      s"$targetBucketName${FileName.SEPARATOR}$targetObjectName")

    def targetMetadata = transport.fsManager.resolveFile(
      s"$targetBucketName${FileName.SEPARATOR}$targetObjectName.metadata")

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
      _ <- Future {
        file.moveTo(target)
        if (targetMetadata.exists())
          metadataFile.moveTo(targetMetadata)
      }
    } yield ()
  }

  private val copySelector = new FileTypeSelector(FileType.FILE)

  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext): Future[Unit] = {
    def target = transport.fsManager.resolveFile(
      s"$targetBucketName${FileName.SEPARATOR}$targetObjectName")

    def targetMetadata = transport.fsManager.resolveFile(
      s"$targetBucketName${FileName.SEPARATOR}$targetObjectName.metadata")

    Future {
      target.copyFrom(file, copySelector)
      if (targetMetadata.exists())
        targetMetadata.copyFrom(metadataFile, copySelector)
    }
  }

  // Utility methods
  @inline private def file =
    transport.fsManager.resolveFile(s"$bucket${FileName.SEPARATOR}$name")

  @inline private def metadataFile =
    transport.fsManager.resolveFile(s"$bucket${FileName.SEPARATOR}$name.metadata")

  override lazy val toString = s"VFSObjectRef($bucket, $name)"

  def versioning: Option[ObjectVersioning] = None
}

object VFSObjectRef {
  def defaultThreshold: Bytes = Bytes(8192L)
}

package com.zengularity.benji.google

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Source, StreamConverters }
import akka.util.ByteString

import com.zengularity.benji.{ ByteRange, VersionedObjectRef, Bytes }

final case class GoogleVersionedObjectRef(storage: GoogleStorage, bucket: String, name: String, versionId: String)
  extends VersionedObjectRef {
  @inline def defaultThreshold: Bytes = GoogleObjectRef.defaultThreshold

  @inline private def gt = storage.transport

  private val generation: Long = versionId.toLong

  private case class GoogleDeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      val futureResult = Future { gt.client.objects().delete(bucket, name).setGeneration(generation).execute(); () }
      if (ignoreExists) {
        futureResult.recover { case HttpResponse(404, _) => () }
      } else {
        futureResult.recoverWith {
          case HttpResponse(404, _) =>
            Future.failed[Unit](new IllegalArgumentException(s"Could not delete $bucket/$name/$generation: doesn't exist"))
        }
      }
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  /**
   * Prepares a request to delete the referenced object
   */
  val delete: DeleteRequest = GoogleDeleteRequest()

  /**
   * Prepares the request to get the contents of this specific version.
   */
  val get: GetRequest = new GoogleGetRequest()

  final class GoogleGetRequest private[google] () extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        val req = gt.client.objects().get(bucket, name).setGeneration(generation)

        req.setRequestHeaders {
          val headers = new com.google.api.client.http.HttpHeaders()

          range.foreach { r =>
            headers.setRange(s"bytes=${r.start}-${r.end}")
          }

          headers
        }

        req.setDisableGZipContent(storage.disableGZip)

        req.executeMediaAsInputStream() // using alt=media
      }.map(in => StreamConverters.fromInputStream(() => in)).recoverWith {
        case HttpResponse(status, msg) =>
          Future.failed[Source[ByteString, NotUsed]](new IllegalStateException(s"Could not get the contents of the object $name in the bucket $bucket. Response: $status - $msg"))

        case cause =>
          Future.failed[Source[ByteString, NotUsed]](cause)
      }).flatMapMerge(1, identity)
    }
  }

  /**
   * Determines whether or not this version exists.
   * `false` might be returned also in cases where you don't have permission
   * to view a certain object.
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] = Future {
    gt.client.objects().get(bucket, name).setGeneration(generation).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }
}

/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Source, StreamConverters }
import akka.util.ByteString

import play.api.libs.json.{ JsObject, JsString, Json }

import com.zengularity.benji.{ ByteRange, VersionedObjectRef }
import com.zengularity.benji.exception.VersionNotFoundException

/** A live reference to a versioned object on Google Cloud Storage. */
final class GoogleVersionedObjectRef(
  storage: GoogleStorage,
  val bucket: String,
  val name: String,
  val versionId: String) extends VersionedObjectRef { ref =>

  @inline private def gt = storage.transport

  private val generation: Long = versionId.toLong

  private case class GoogleDeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    def apply()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      val rawResult = Future { gt.client.objects().delete(bucket, name).setGeneration(generation).execute(); () }
      val result = rawResult.recoverWith(ErrorHandler.ofVersionToFuture(s"Could not delete version $versionId from object $name within bucket $bucket", ref))
      if (ignoreExists) {
        result.recover { case VersionNotFoundException(_, _, _) => () }
      } else {
        result
      }
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  val delete: DeleteRequest = GoogleDeleteRequest()

  val get: GetRequest = new GoogleGetRequest()

  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = Future {
    val resp = gt.client.objects().get(bucket, name).setGeneration(generation).executeUnparsed()

    resp.parseAsString
  }.flatMap {
    Json.parse(_) match {
      case JsObject(fields) => Future(fields.toMap.flatMap {
        case (key, JsString(value)) => Seq(key -> Seq(value))

        case ("metadata", JsObject(metadata)) => metadata.collect {
          case (key, JsString(value)) => s"metadata.$key" -> Seq(value)
        }

        case _ => Seq.empty[(String, Seq[String])] // unsupported
      })

      case js => Future.failed[Map[String, Seq[String]]](new IllegalStateException(s"Could not get the headers of version $versionId of the object $name in the bucket $bucket. JSON response: ${Json stringify js}"))
    }
  }.recoverWith(ErrorHandler.ofVersionToFuture(s"Could not get the headers of version $versionId of the object $name in the bucket $bucket", ref))

  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = headers().map { headers =>
    headers.collect { case (key, value) if key.startsWith("metadata") => key.stripPrefix("metadata.") -> value }
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

  override lazy val toString =
    s"GoogleVersionedObjectRef($bucket, $name, $versionId)"

  override def equals(that: Any): Boolean = that match {
    case other: GoogleVersionedObjectRef =>
      other.tupled == this.tupled

    case _ => false
  }

  override def hashCode: Int = tupled.hashCode

  @inline private def tupled = (bucket, name, versionId)

  // ---

  private final class GoogleGetRequest private[google] () extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFutureSource(Future {
        val req = gt.client.objects().get(bucket, name).setGeneration(generation)

        req.setRequestHeaders {
          val headers = new com.google.api.client.http.HttpHeaders()

          range.foreach { r =>
            headers.setRange(s"bytes=${r.start.toString}-${r.end.toString}")
          }

          headers
        }

        req.setDisableGZipContent(storage.disableGZip)

        val in = req.executeMediaAsInputStream() // using alt=media
        StreamConverters.fromInputStream(() => in)
      }.recoverWith(ErrorHandler.ofVersionToFuture(s"Could not get the contents of the version $versionId in object $name in the bucket $bucket", ref)))
    }.mapMaterializedValue(_ => NotUsed)
  }
}

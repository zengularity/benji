package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.google.api.services.storage.model
import com.google.api.services.storage.model.StorageObject

import com.zengularity.benji.{ BucketRef, Bytes, Object }

final class GoogleBucketRef private[google] (
  val storage: GoogleStorage,
  val name: String) extends BucketRef[GoogleStorage] { ref =>
  import scala.collection.JavaConverters.collectionAsScalaIterable

  object objects extends ref.ListRequest {
    def apply()(implicit m: Materializer, gt: GoogleTransport): Source[Object, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        def req = gt.client.objects().list(name).execute()
        // TODO: Usage pagination + new withChunkSize

        Source[Object](Option(req.getItems).
          map(collectionAsScalaIterable(_).toList).
          getOrElse(List.empty[StorageObject]).map { obj =>
            Object(
              obj.getName,
              Bytes(obj.getSize.longValue),
              LocalDateTime.ofInstant(Instant.ofEpochMilli(obj.getUpdated.getValue), ZoneOffset.UTC))
          })
      }).flatMapMerge(1, identity)
    }
  }

  def exists(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Boolean] = Future {
    gt.client.buckets().get(name).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }

  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Boolean] = {
    if (!checkBefore) create
    else exists.flatMap {
      case true => Future.successful(false)
      case _ => create
    }
  }

  private def create(implicit ec: ExecutionContext, gt: GoogleTransport): Future[Boolean] = Future {
    val nb = new model.Bucket()
    nb.setName(name)

    gt.client.buckets().insert(gt.projectId, nb).execute()
  }.map(_ => true).recoverWith {
    case HttpResponse(409, "Conflict") => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }

  private def emptyBucket()(implicit m: Materializer, gt: GoogleTransport): Future[Unit] = {
    implicit val ec = m.executionContext
    Future(objects()).flatMap(_.runFoldAsync(())((_: Unit, e) => obj(e.name).delete.ignoreIfNotExists()))
  }

  private case class GoogleDeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    private def delete()(implicit m: Materializer, tr: Transport): Future[Unit] = {
      implicit val ec = m.executionContext
      val futureResult = Future { tr.client.buckets().delete(name).execute(); () }
      if (ignoreExists) {
        futureResult.recover { case HttpResponse(404, _) => () }
      } else {
        futureResult
      }
    }

    def apply()(implicit m: Materializer, tr: Transport): Future[Unit] = {
      implicit val ec = m.executionContext
      if (isRecursive) emptyBucket().flatMap(_ => delete())
      else delete()
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)

    def recursive: DeleteRequest = this.copy(isRecursive = true)
  }

  def delete: DeleteRequest = GoogleDeleteRequest()

  def obj(objectName: String): GoogleObjectRef =
    new GoogleObjectRef(storage, name, objectName)

  override lazy val toString = s"GoogleBucketRef($name)"
}

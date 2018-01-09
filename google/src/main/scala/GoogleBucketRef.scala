package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.google.api.services.storage.model

import com.zengularity.benji.{ BucketRef, Bytes, Object }

final class GoogleBucketRef private[google] (
  val storage: GoogleStorage,
  val name: String) extends BucketRef { ref =>
  import scala.collection.JavaConverters.collectionAsScalaIterable

  @inline private def gt = storage.transport

  private case class Objects(maybeMax: Option[Long]) extends ref.ListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    private def apply(nextToken: Option[String])(implicit m: Materializer): Source[Object, NotUsed] = {
      val prepared = gt.client.objects().list(name)
      val maxed = maybeMax.fold(prepared) { prepared.setMaxResults(_) }

      val request =
        nextToken.fold(maxed.execute()) { maxed.setPageToken(_).execute() }

      val currentPage = Option(request.getItems) match {
        case Some(items) => Source.fromIterator[Object] { () =>
          collectionAsScalaIterable(items).iterator.map { obj =>
            Object(obj.getName, Bytes(obj.getSize.longValue),
              LocalDateTime.ofInstant(Instant.ofEpochMilli(obj.getUpdated.getValue), ZoneOffset.UTC))
          }
        }

        case _ => Source.empty[Object]
      }

      Option(request.getNextPageToken) match {
        case nextPageToken @ Some(_) =>
          currentPage ++ apply(nextPageToken)

        case _ => currentPage
      }
    }

    def apply()(implicit m: Materializer): Source[Object, NotUsed] = apply(None)
  }

  def objects: ListRequest = Objects(None)

  def exists(implicit ec: ExecutionContext): Future[Boolean] = Future {
    gt.client.buckets().get(name).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }

  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = {
    if (!checkBefore) create
    else exists.flatMap {
      case true => Future.successful(false)
      case _ => create
    }
  }

  private def create(implicit ec: ExecutionContext): Future[Boolean] = Future {
    val nb = new model.Bucket()
    nb.setName(name)

    gt.client.buckets().insert(gt.projectId, nb).execute()
  }.map(_ => true).recoverWith {
    case HttpResponse(409, "Conflict") => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }

  private def emptyBucket()(implicit m: Materializer): Future[Unit] = {
    implicit val ec = m.executionContext
    Future(objects()).flatMap(_.runFoldAsync(())((_: Unit, e) => obj(e.name).delete.ignoreIfNotExists()))
  }

  private case class GoogleDeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    private def delete()(implicit m: Materializer): Future[Unit] = {
      implicit val ec = m.executionContext
      val futureResult = Future { gt.client.buckets().delete(name).execute(); () }
      if (ignoreExists) {
        futureResult.recover { case HttpResponse(404, _) => () }
      } else {
        futureResult
      }
    }

    def apply()(implicit m: Materializer): Future[Unit] = {
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

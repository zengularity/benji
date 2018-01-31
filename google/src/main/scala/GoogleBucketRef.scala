package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.google.api.services.storage.model
import com.google.api.services.storage.model.StorageObject

import com.zengularity.benji.{ BucketRef, BucketVersioning, Bytes, Object, VersionedObject, VersionedObjectRef }

import play.api.libs.json.{ JsBoolean, JsDefined, Json }

final class GoogleBucketRef private[google] (
  val storage: GoogleStorage,
  val name: String) extends BucketRef with BucketVersioning { ref =>
  import scala.collection.JavaConverters.collectionAsScalaIterable

  @inline private def gt = storage.transport

  private case class Objects(maybeMax: Option[Long]) extends ref.ListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def apply()(implicit m: Materializer): Source[Object, NotUsed] = list(None)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def list(nextToken: Option[String])(implicit m: Materializer): Source[Object, NotUsed] = {
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
          currentPage ++ list(nextPageToken)

        case _ => currentPage
      }
    }
  }

  def objects: ListRequest = Objects(None)

  def exists(implicit ec: ExecutionContext): Future[Boolean] = Future {
    gt.client.buckets().get(name).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err => Future.failed[Boolean](err)
  }

  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = {
    if (!checkBefore) createNew
    else exists.flatMap {
      case true => Future.successful(false)
      case _ => createNew
    }
  }

  private def createNew(implicit ec: ExecutionContext): Future[Boolean] =
    Future {
      val nb = new model.Bucket()
      nb.setName(name)

      gt.client.buckets().insert(gt.projectId, nb).execute()
    }.map(_ => true).recoverWith {
      case HttpResponse(409, "Conflict") => Future.successful(false)
      case err => Future.failed[Boolean](err)
    }

  private def emptyBucket()(implicit m: Materializer): Future[Unit] = {
    implicit val ec: ExecutionContext = m.executionContext

    Future(objectsVersions()).flatMap(_.runFoldAsync(())((_: Unit, e) => obj(e.name, e.versionId).delete.ignoreIfNotExists()))
  }

  private case class GoogleDeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    private def delete()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      val futureResult = Future { gt.client.buckets().delete(name).execute(); () }
      if (ignoreExists) {
        futureResult.recover { case HttpResponse(404, _) => () }
      } else {
        futureResult
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

  def delete: DeleteRequest = GoogleDeleteRequest()

  def obj(objectName: String): GoogleObjectRef =
    new GoogleObjectRef(storage, name, objectName)

  override lazy val toString = s"GoogleBucketRef($name)"

  def versioning: Option[BucketVersioning] = Some(this)

  /**
   * Checks whether the versioning is currently enabled or not on this bucket.
   *
   * @return A future with true if versioning is currently enabled, otherwise a future with false.
   */
  def isVersioned(implicit ec: ExecutionContext): Future[Boolean] =
    gt.withWSRequest1("", s"/b/$name?fields=versioning")(_.get).flatMap { response =>
      val json = Json.parse(response.body)
      json \ "versioning" \ "enabled" match {
        case JsDefined(JsBoolean(enabled)) => Future.successful(enabled)
        case _ => Future.successful(false) // JSON is empty when bucket versioning has never been configured
      }
    }

  /**
   * Enables or disables the versioning of objects on this bucket.
   */
  def setVersioning(enabled: Boolean)(implicit ec: ExecutionContext): Future[Unit] =
    Future {
      val versioning = new com.google.api.services.storage.model.Bucket.Versioning()
      versioning.setEnabled(enabled)
      val bucket = new com.google.api.services.storage.model.Bucket()
      bucket.setVersioning(versioning)
      gt.client.buckets().patch(name, bucket).execute()
      ()
    }

  private case class ObjectsVersions(maybeMax: Option[Long]) extends ref.VersionedListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    private def apply(nextToken: Option[String])(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      val prepared = gt.client.objects().list(name).setVersions(true)
      val maxed = maybeMax.fold(prepared) { prepared.setMaxResults(_) }

      val request =
        nextToken.fold(maxed.execute()) { maxed.setPageToken(_).execute() }

      val currentPage = Option(request.getItems) match {
        case Some(items) =>
          Source.fromIterator[VersionedObject] { () =>
            val collection = collectionAsScalaIterable(items)
            collection.iterator.map { obj: StorageObject =>
              VersionedObject(
                obj.getName,
                Bytes(obj.getSize.longValue),
                LocalDateTime.ofInstant(Instant.ofEpochMilli(obj.getUpdated.getValue), ZoneOffset.UTC),
                obj.getGeneration.toString,
                obj.getTimeDeleted == null)
            }
          }

        case _ => Source.empty[VersionedObject]
      }

      Option(request.getNextPageToken) match {
        case nextPageToken @ Some(_) =>
          currentPage ++ apply(nextPageToken)

        case _ => currentPage
      }
    }

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = apply(None)
  }

  /**
   * Prepares a request to list the bucket versioned objects.
   */
  def objectsVersions: VersionedListRequest = ObjectsVersions(None)

  /**
   * Gets a reference to a specific version of an object, allowing you to perform operations on an object version.
   */
  def obj(objectName: String, versionId: String): VersionedObjectRef = GoogleVersionedObjectRef(storage, name, objectName, versionId)
}

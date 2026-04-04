/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.json.{ JsBoolean, JsDefined, JsObject, JsUndefined, Json }

import com.google.api.services.storage.model

import com.github.ghik.silencer.silent
import com.zengularity.benji.{
  BucketRef,
  BucketVersioning,
  Bytes,
  Compat,
  Object,
  VersionedObject,
  VersionedObjectRef
}
import com.zengularity.benji.exception.{
  BenjiUnknownError,
  BucketAlreadyExistsException,
  BucketNotFoundException
}

import model.StorageObject

final class GoogleBucketRef private[google] (
    storage: GoogleStorage,
    val name: String)
    extends BucketRef
    with BucketVersioning { ref =>

  import Compat.javaConverters._

  import storage.{ transport => gt }

  private case class Objects(
      maybePrefix: Option[String],
      maybeMax: Option[Long])
      extends ref.ListRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Source[Object, NotUsed] = list(None)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    @silent(".*fromFutureSource.*")
    private def list(
        nextToken: Option[String]
      )(implicit
        m: Materializer
      ): Source[Object, NotUsed] = {
      implicit val ec: ExecutionContext = m.executionContext

      Source.fromFutureSource(
        Future {
          val prepared = gt.client.objects().list(name)
          val maxed = maybeMax.fold(prepared)(prepared.setMaxResults(_))
          val prefixed = maybePrefix.fold(maxed)(maxed.setPrefix(_))

          val request = nextToken.fold(prefixed.execute()) {
            prefixed.setPageToken(_).execute()
          }

          val currentPage = Option(request.getItems) match {
            case Some(items) =>
              Source.fromIterator[Object] { () =>
                collectionAsScalaIterable(items).iterator.map { obj =>
                  Object(
                    obj.getName,
                    Bytes(obj.getSize.longValue),
                    LocalDateTime.ofInstant(
                      Instant.ofEpochMilli(obj.getUpdated.getValue),
                      ZoneOffset.UTC
                    )
                  )
                }
              }

            case _ => Source.empty[Object]
          }

          Option(request.getNextPageToken) match {
            case nextPageToken @ Some(_) =>
              currentPage ++ list(nextPageToken)

            case _ => currentPage
          }
        }.recoverWith(
          ErrorHandler.ofBucketToFuture(
            s"Could not list objects in bucket $name",
            ref
          )
        )
      )
    }.mapMaterializedValue(_ => NotUsed)

    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def withPrefix(prefix: String) = this.copy(maybePrefix = Some(prefix))
  }

  def objects: ListRequest = Objects(None, None)

  def exists(
      implicit
      ec: ExecutionContext
    ): Future[Boolean] = Future {
    gt.client.buckets().get(name).executeUsingHead()
  }.map(_ => true).recoverWith {
    case HttpResponse(404, _) => Future.successful(false)
    case err                  => Future.failed[Boolean](err)
  }

  def create(
      failsIfExists: Boolean = false
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] = gt
    .executeBucketOp(GoogleTransport.CreateBucket(name))
    .recoverWith(
      ErrorHandler.ofBucketToFuture(s"Could not create bucket $name", ref)
    )
    .recoverWith {
      case BucketAlreadyExistsException(_) if !failsIfExists =>
        Future.successful({})
    }

  private def emptyBucket(
    )(implicit
      m: Materializer
    ): Future[Unit] = {
    implicit val ec: ExecutionContext = m.executionContext

    Future(versionedObjects()).flatMap(_.runFoldAsync(()) { (_: Unit, e) =>
      obj(e.name, e.versionId).delete.ignoreIfNotExists()
    })
  }

  private case class GoogleDeleteRequest(
      isRecursive: Boolean = false,
      ignoreExists: Boolean = false)
      extends DeleteRequest {

    private def isTransientDeleteError(error: Throwable): Boolean = {
      val marker = "unexpected end of file"

      error match {
        case BenjiUnknownError(msg, cause) =>
          val fromCause = cause.map(_.getMessage).getOrElse("")
          s"$msg $fromCause".toLowerCase.contains(marker)

        case ioe: java.io.IOException =>
          Option(ioe.getMessage).exists(_.toLowerCase.contains(marker))

        case _ => false
      }
    }

    private def delete(
      )(implicit
        ec: ExecutionContext
      ): Future[Unit] =
      gt.executeBucketOp(GoogleTransport.DeleteBucket(name))

    def apply(
      )(implicit
        m: Materializer
      ): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      val rawResult = {
        if (!isRecursive) delete()
        else emptyBucket().flatMap(_ => delete())
      }

      val result = rawResult.recoverWith(
        ErrorHandler.ofBucketToFuture(s"Could not delete bucket $name", ref)
      )

      if (ignoreExists) {
        result.recoverWith {
          case BucketNotFoundException(_) => Future.successful(())

          case error if isTransientDeleteError(error) =>
            exists.flatMap {
              case false => Future.successful(())
              case true  => Future.failed(error)
            }.recoverWith {
              case _ =>
                Future.failed(error)
            }
        }
      } else {
        result
      }
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)

    def recursive: DeleteRequest = this.copy(isRecursive = true)
  }

  def delete: DeleteRequest = GoogleDeleteRequest()

  def obj(objectName: String): GoogleObjectRef =
    new GoogleObjectRef(storage, name, objectName)

  def versioning: Option[BucketVersioning] = Some(this)

  def isVersioned(
      implicit
      ec: ExecutionContext
    ): Future[Boolean] = {
    gt.withWSRequest1("", s"/b/$name?fields=versioning")(_.get()).flatMap {
      response =>
        val json = Json.parse(response.body)

        json match {
          case JsObject(m) if m.isEmpty =>
            // JSON is empty when bucket versioning has never been configured
            Future.successful(false)

          case JsObject(m) if m.contains("versioning") =>
            json \ "versioning" match {
              // Testbench may return {"versioning": {}} for disabled
              case JsDefined(JsObject(v)) if v.isEmpty =>
                Future.successful(false)

              case _ =>
                json \ "versioning" \ "enabled" match {
                  case JsDefined(JsBoolean(enabled)) =>
                    Future.successful(enabled)

                  case e: JsUndefined =>
                    Future.failed[Boolean](
                      new java.io.IOException(
                        s"Could not parse versioning result: ${e.error}"
                      )
                    )

                  case JsDefined(j) =>
                    Future.failed[Boolean](
                      new java.io.IOException(
                        s"Could not parse versioning result: unexpected value ${Json stringify j}"
                      )
                    )
                }
            }

          case _ =>
            ErrorHandler.ofBucketFromResponse(
              s"Could not get versioning state of bucket $name",
              ref
            )(response)
        }
    }
  }.recoverWith(
    ErrorHandler
      .ofBucketToFuture(s"Could not get versioning state of bucket $name", ref)
  )

  /**
   * Enables or disables the versioning of objects on this bucket.
   */
  def setVersioning(
      enabled: Boolean
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] =
    Future {
      val versioning =
        new com.google.api.services.storage.model.Bucket.Versioning()
      versioning.setEnabled(enabled)
      val bucket = new com.google.api.services.storage.model.Bucket()
      bucket.setVersioning(versioning)
      gt.client.buckets().patch(name, bucket).execute()
      ()
    }.recoverWith(
      ErrorHandler.ofBucketToFuture(
        s"Could not change versioning state of bucket $name",
        ref
      )
    )

  private case class ObjectsVersions(
      maybePrefix: Option[String],
      maybeMax: Option[Long])
      extends ref.VersionedListRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Source[VersionedObject, NotUsed] =
      list(None)

    @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
    @silent(".*fromFutureSource.*")
    private def list(
        nextToken: Option[String]
      )(implicit
        m: Materializer
      ): Source[VersionedObject, NotUsed] = {
      implicit val ec: ExecutionContext = m.executionContext

      Source.fromFutureSource(
        Future {
          val prepared = gt.client.objects().list(name).setVersions(true)
          val maxed = maybeMax.fold(prepared)(prepared.setMaxResults(_))
          val prefixed = maybePrefix.fold(maxed)(maxed.setPrefix(_))

          val request = nextToken.fold(prefixed.execute()) {
            prefixed.setPageToken(_).execute()
          }

          val currentPage = Option(request.getItems) match {
            case Some(items) =>
              val collection = collectionAsScalaIterable(items).toList

              // Group by name to find the latest generation per object
              val latestGenerations: Map[String, Long] =
                collection.groupBy(_.getName).map {
                  case (n, objs) =>
                    n -> objs.map(_.getGeneration.longValue).max
                }

              Source.fromIterator[VersionedObject] { () =>
                collection.iterator.map { (obj: StorageObject) =>
                  val isLatest = obj.getTimeDeleted == null &&
                    obj.getGeneration.longValue == latestGenerations.getOrElse(
                      obj.getName,
                      -1L
                    )

                  VersionedObject(
                    obj.getName,
                    Bytes(obj.getSize.longValue),
                    LocalDateTime.ofInstant(
                      Instant.ofEpochMilli(obj.getUpdated.getValue),
                      ZoneOffset.UTC
                    ),
                    obj.getGeneration.toString,
                    isLatest
                  )
                }
              }

            case _ => Source.empty[VersionedObject]
          }

          Option(request.getNextPageToken) match {
            case nextPageToken @ Some(_) =>
              currentPage ++ list(nextPageToken)

            case _ => currentPage
          }
        }.recoverWith(
          ErrorHandler.ofBucketToFuture(
            s"Could not list versions in bucket $name",
            ref
          )
        )
      )
    }.mapMaterializedValue(_ => NotUsed)

    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def withPrefix(prefix: String) = this.copy(maybePrefix = Some(prefix))
  }

  def versionedObjects: VersionedListRequest = ObjectsVersions(None, None)

  def obj(objectName: String, versionId: String): VersionedObjectRef =
    new GoogleVersionedObjectRef(storage, name, objectName, versionId)

  @inline private def collectionAsScalaIterable[A](
      i: java.util.Collection[A]
    ): Iterable[A] = i.asScala

  override lazy val toString = s"GoogleBucketRef($name)"

  override def equals(that: Any): Boolean = that match {
    case other: GoogleBucketRef =>
      other.name == this.name

    case _ => false
  }

  override def hashCode: Int = name.hashCode
}

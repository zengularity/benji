/*
 * Copyright (C) 2018-2018 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import scala.xml.Elem
import scala.collection.immutable.Iterable
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.StandaloneWSResponse

import com.zengularity.benji.{ BucketRef, BucketVersioning, Object, VersionedObject }
import com.zengularity.benji.exception.{ BucketAlreadyExistsException, BucketNotFoundException }
import com.zengularity.benji.s3.QueryParameters._
import com.zengularity.benji.ws.Successful

final class WSS3BucketRef private[s3] (
  val storage: WSS3,
  val name: String) extends BucketRef with BucketVersioning { ref =>

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  private case class Objects(maybeMax: Option[Long]) extends ref.ListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def apply()(implicit m: Materializer): Source[Object, NotUsed] = {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def next(nextToken: String): Source[Object, NotUsed] =
        list(Some(nextToken))(next(_))

      list(Option.empty[String])(next(_))
    }

    def list(token: Option[String])(andThen: String => Source[Object, NotUsed])(implicit m: Materializer): Source[Object, NotUsed] = {
      val parse: Elem => Iterable[Object] = { xml =>
        (xml \ "Contents").map(Xml.objectFromXml)
      }

      val query: Option[String] => Option[String] = { token =>
        buildQuery(maxParam(maybeMax), tokenParam(token))
      }

      val errorHandler = ErrorHandler.ofBucket(s"Could not list objects within the bucket $name", ref)(_)

      WSS3BucketRef.list[Object](ref.storage, ref.name, token, errorHandler)(
        query, parse, _.name, andThen)
    }
  }

  def objects: ListRequest = Objects(None)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketHEAD.html
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage.request(Some(name), requestTimeout = requestTimeout).
      head().map(_.status == 200)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUT.html
   */
  def create(failsIfExists: Boolean = false)(implicit ec: ExecutionContext): Future[Unit] = {
    val before = if (failsIfExists) {
      exists.flatMap {
        case true => Future.failed[Unit](BucketAlreadyExistsException(name))
        case false => Future.successful({})
      }
    } else {
      Future.successful({})
    }
    before.flatMap(_ => createNew(failsIfExists))
  }

  private def createNew(failsIfExists: Boolean)(implicit ec: ExecutionContext): Future[Unit] = {
    import play.api.libs.ws.DefaultBodyWritables._

    storage.request(Some(name), requestTimeout = requestTimeout).put("").flatMap {
      case Successful(_) =>
        Future.successful(logger.info(s"Successfully created the bucket $name."))

      case response =>
        ErrorHandler.ofBucket(s"Could not create the bucket $name.", ref)(response) match {
          case BucketAlreadyExistsException(_) if !failsIfExists => Future.successful({})
          case throwable => Future.failed[Unit](throwable)
        }
    }
  }

  private def emptyBucket()(implicit m: Materializer): Future[Unit] = {
    // We want to delete all versions including deleteMarkers for this bucket to be considered empty.
    ObjectsVersions().withDeleteMarkers().runFoldAsync(())((_: Unit, e) => {
      // As we will delete all deleteMarkers we disable the auto-delete of deleteMarkers using "skipMarkersCheck"
      obj(e.name, e.versionId).WSS3DeleteRequest().skipMarkersCheck.ignoreIfNotExists()
    })
  }

  private case class WSS3DeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketDELETE.html
     */
    private def delete()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      storage.request(Some(name), requestTimeout = requestTimeout).
        delete().flatMap {
          case Successful(_) =>
            Future.successful(logger.info(
              s"Successfully deleted the bucket $name."))

          case response => ErrorHandler.ofBucket(s"Could not delete the bucket $name", ref)(response) match {
            case BucketNotFoundException(_) if ignoreExists =>
              Future.successful(logger.info(s"Bucket $name was not found when deleting. (Success)"))
            case throwable => Future.failed[Unit](throwable)
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

  def delete: DeleteRequest = WSS3DeleteRequest()

  def obj(objectName: String): WSS3ObjectRef =
    new WSS3ObjectRef(storage, name, objectName)

  override val toString: String = s"WSS3BucketRef($name)"

  def versioning: Option[BucketVersioning] = Some(this)

  def isVersioned(implicit ec: ExecutionContext): Future[Boolean] = {
    val request = storage.request(
      Some(name),
      requestTimeout = storage.requestTimeout,
      query = Some("versioning")).get()

    request.flatMap({
      case Successful(response) =>
        val xml = scala.xml.XML.loadString(response.body)
        xml \ "Status" match {
          case Seq(n) if n.text == "Enabled" => Future.successful(true)
          case Seq(n) if n.text == "Suspended" => Future.successful(false)
          case Seq() => Future.successful(false)
          case s =>
            Future.failed[Boolean](new IllegalStateException(s"Unexpected multiple VersioningConfiguration.Status children from S3: $s"))
        }
      case response =>
        val error = ErrorHandler.ofBucket(s"Could not check versioning of bucket $name", ref)(response)
        Future.failed[Boolean](error)
    })
  }

  def setVersioning(enabled: Boolean)(implicit ec: ExecutionContext): Future[Unit] = {
    import play.api.libs.ws.DefaultBodyWritables._

    val body =
      <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
        <Status>{ if (enabled) "Enabled" else "Suspended" }</Status>
      </VersioningConfiguration>
    val req = storage.request(Some(name), requestTimeout = storage.requestTimeout, query = Some("versioning"))

    req.put(body.toString()).flatMap {
      case Successful(_) => Future.unit
      case response =>
        val error = ErrorHandler.ofBucket(s"Could not change versionning of the bucket $name", ref)(response)
        Future.failed[Unit](error)
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  private case class ObjectsVersions(maybeMax: Option[Long] = None, includeDeleteMarkers: Boolean = false) extends ref.VersionedListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def withDeleteMarkers = this.copy(includeDeleteMarkers = true)

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def next(nextToken: String): Source[VersionedObject, NotUsed] =
        list(Some(nextToken))(next(_))

      list(Option.empty[String])(next(_))
    }

    def list(token: Option[String])(andThen: String => Source[VersionedObject, NotUsed])(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      val parse: Elem => Iterable[VersionedObject] = { xml =>
        if (includeDeleteMarkers) {
          (xml \ "Version").map(Xml.versionDecoder) ++ (xml \ "DeleteMarker").map(Xml.deleteMarkerDecoder)
        } else {
          (xml \ "Version").map(Xml.versionDecoder)
        }
      }

      val query: Option[String] => Option[String] = { token =>
        buildQuery(versionParam, maxParam(maybeMax), tokenParam(token))
      }

      val errorHandler = ErrorHandler.ofBucket(s"Could not list versions within the bucket $name", ref)(_)

      WSS3BucketRef.list[VersionedObject](ref.storage, ref.name, token, errorHandler)(query, parse, _.name, andThen)
    }

  }

  def objectsVersions: VersionedListRequest = ObjectsVersions()

  def obj(objectName: String, versionId: String): WSS3VersionedObjectRef = WSS3VersionedObjectRef(storage, name, objectName, versionId)
}

object WSS3BucketRef {
  /**
   * @param name the bucket name
   * @param token the continuation token
   */
  private[s3] def list[T](storage: WSS3, name: String, token: Option[String], errorHandler: StandaloneWSResponse => Throwable)(
    query: Option[String] => Option[String],
    parse: Elem => Iterable[T],
    marker: T => String,
    andThen: String => Source[T, NotUsed],
    whenEmpty: Option[Throwable] = None)(implicit m: Materializer): Source[T, NotUsed] = {
    @inline def requestTimeout = storage.requestTimeout

    val request = storage.request(
      Some(name), requestTimeout = requestTimeout, query = query(token))

    S3.getXml[T](request)({ xml =>
      def isTruncated = (xml \ "IsTruncated").text.toBoolean

      val parsed = parse(xml)

      whenEmpty match {
        case Some(throwable) if parsed.isEmpty => Source.failed[T](throwable)
        case _ =>
          def currentPage = Source(parsed)

          parsed.lastOption.map(marker) match {
            case Some(tok) if isTruncated =>
              currentPage ++ andThen(tok)

            case _ => currentPage
          }
      }
    }, errorHandler)
  }
}

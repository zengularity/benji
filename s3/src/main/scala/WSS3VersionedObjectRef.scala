/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ ByteRange, Bytes, Compat, VersionedObject, VersionedObjectRef }
import com.zengularity.benji.exception.{ ObjectNotFoundException, VersionNotFoundException }
import com.zengularity.benji.ws.Successful

final class WSS3VersionedObjectRef(
  storage: WSS3,
  val bucket: String,
  val name: String,
  val versionId: String) extends VersionedObjectRef { ref =>

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  def delete: DeleteRequest = WSS3DeleteRequest()

  val get: GetRequest = RESTGetRequest

  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = {
    def req = storage.request(
      Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout)

    req.head().flatMap {
      case response if response.status == 200 =>
        Future(Compat.mapValues(response.headers)(_.toSeq))

      case response =>
        val error = ErrorHandler.ofVersion(s"Could not get the head of version $versionId of the object $name in the bucket $bucket", ref)(response)
        Future.failed[Map[String, Seq[String]]](error)
    }
  }

  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]] = headers().map { headers =>
    headers.collect { case (key, value) if key.startsWith("x-amz-meta-") => key.stripPrefix("x-amz-meta-") -> value }
  }

  /**
   * @see [[http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html RESTObjectHEAD]]
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage.request(Some(bucket), Some(name),
      query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout).
      head().map(_.status == 200)

  override lazy val toString =
    s"WSS3VersionedObjectRef($bucket, $name, $versionId)"

  override def equals(that: Any): Boolean = that match {
    case other: WSS3VersionedObjectRef =>
      other.tupled == this.tupled

    case _ => false
  }

  override def hashCode: Int = tupled.hashCode

  @inline private def tupled = (bucket, name, versionId)

  // ---

  private[s3] case class WSS3DeleteRequest(ignoreExists: Boolean = false, skipMarkers: Boolean = false) extends DeleteRequest {

    @inline
    private def isDeleteMarker(version: VersionedObject) = version.size.bytes == -1

    private def markersToDelete()(implicit m: Materializer): Future[Seq[VersionedObject]] = {
      implicit val ec: ExecutionContext = m.executionContext

      new WSS3ObjectRef(storage, bucket, name).ObjectVersions().
        withDeleteMarkers.collect[List]().flatMap(versionsWithSelf => {
          if (!versionsWithSelf.exists(_.versionId == versionId)) {
            Future.failed[Seq[VersionedObject]](VersionNotFoundException(ref))
          } else {
            // versions as they'll be after deleting this VersionedObjectRef
            val versions = versionsWithSelf.filter(_.versionId != versionId)

            // There are two cases where we automatically delete some deleteMarkers after deleting this version :
            //  1. When there will be only deleteMarkers left, we completely delete the object (forall condition)
            //  2. Otherwise, we will delete deleteMarkers that are not currently the latest version (filter condition)
            if (versions.forall(isDeleteMarker)) {
              Future.successful(versions)
            } else {
              Future.successful(versions.filter(v => isDeleteMarker(v) && !v.isLatest))
            }
          }
        }).recoverWith { case ObjectNotFoundException(_, _) => Future.failed[Seq[VersionedObject]](VersionNotFoundException(ref)) }
    }

    private def deleteSingle(v: VersionedObject)(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      storage.request(Some(bucket), Some(v.name), query = Some(s"versionId=${v.versionId}"), requestTimeout = requestTimeout).delete().flatMap {
        case Successful(_) =>
          Future.successful(logger.info(s"Successfully deleted the version $bucket/${v.name}/${v.versionId}."))

        case response =>
          val errorHandler = ErrorHandler.ofVersion(s"Could not delete version $versionId from object $name within bucket $bucket", ref)(_)
          errorHandler(response) match {
            case VersionNotFoundException(_, _, _) if ignoreExists =>
              Future.successful(logger.info(s"Version $bucket/${v.name}/${v.versionId} was not found when deleting. (Success)"))

            case throwable => Future.failed[Unit](throwable)
          }
      }
    }

    private def multiDeleteSimulated(versions: Seq[VersionedObject])(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      Future.sequence(versions.map(deleteSingle)).map(_ => {})
    }

    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
     */
    def apply()(implicit m: Materializer): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      val self = VersionedObject(name, Bytes(0), java.time.LocalDateTime.MIN, versionId, isLatest = false)

      if (skipMarkers) multiDeleteSimulated(Seq(self))
      else markersToDelete().flatMap(markers => multiDeleteSimulated(self +: markers))
    }

    def ignoreIfNotExists: WSS3DeleteRequest = copy(ignoreExists = true)

    def skipMarkersCheck: WSS3DeleteRequest = copy(skipMarkers = true)
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
   */
  private object RESTGetRequest extends GetRequest {
    @com.github.ghik.silencer.silent(".*fromFutureSource.*")
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      def req = storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout)

      Source.fromFutureSource(range.fold(req)(r => req.addHttpHeaders(
        "Range" -> s"bytes=${r.start.toString}-${r.end.toString}")).withMethod("GET").stream().flatMap {
        case response if response.status == 200 || response.status == 206 =>
          Future.successful(response.bodyAsSource.mapMaterializedValue(_ => NotUsed))

        case response =>
          val err = ErrorHandler.ofVersion(s"Could not get the contents of the version $versionId in object $name in the bucket $bucket", ref)(response)
          Future.failed[Source[ByteString, NotUsed]](err)
      }).mapMaterializedValue(_ => NotUsed)
    }
  }
}

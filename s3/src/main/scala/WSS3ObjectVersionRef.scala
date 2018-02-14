package com.zengularity.benji.s3

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ ByteRange, VersionedObjectRef, VersionedObject, Bytes }
import com.zengularity.benji.exception.{ VersionNotFoundException, ObjectNotFoundException }

import com.zengularity.benji.ws.Successful

final case class WSS3VersionedObjectRef(
  storage: WSS3,
  bucket: String,
  name: String,
  versionId: String) extends VersionedObjectRef { ref =>

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  private[s3] case class WSS3DeleteRequest(ignoreExists: Boolean = false, skipMarkers: Boolean = false) extends DeleteRequest {

    @inline
    private def isDeleteMarker(version: VersionedObject) = version.size.bytes == -1

    private def markersToDelete()(implicit m: Materializer): Future[Seq[VersionedObject]] = {
      implicit val ec: ExecutionContext = m.executionContext

      new WSS3ObjectRef(storage, bucket, name)
        .ObjectsVersions()
        .withDeleteMarkers
        .collect[List]()
        .flatMap(versionsWithSelf => {
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

  def delete: DeleteRequest = WSS3DeleteRequest()

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
   */
  private object RESTGetRequest extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      def req = storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout)

      Source.fromFutureSource(range.fold(req)(r => req.addHttpHeaders(
        "Range" -> s"bytes=${r.start}-${r.end}")).withMethod("GET").stream().flatMap {
        case response if response.status == 200 || response.status == 206 =>
          Future.successful(response.bodyAsSource.mapMaterializedValue(_ => NotUsed))

        case response =>
          val err = ErrorHandler.ofVersion(s"Could not get the contents of the version $versionId in object $name in the bucket $bucket", ref)(response)
          Future.failed[Source[ByteString, NotUsed]](err)
      }).mapMaterializedValue(_ => NotUsed)
    }
  }

  val get: GetRequest = RESTGetRequest

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage.request(Some(bucket), Some(name),
      query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout).
      head().map(_.status == 200)
}

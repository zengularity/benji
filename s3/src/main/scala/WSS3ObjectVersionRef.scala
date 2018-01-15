package com.zengularity.benji.s3

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, Future }

import com.zengularity.benji.{ VersionedObjectRef, ByteRange }
import com.zengularity.benji.ws.Successful

final case class WSS3ObjectVersionRef(storage: WSS3, bucket: String, name: String, versionId: String) extends VersionedObjectRef {
  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  private case class WSS3DeleteRequest(ignoreExists: Boolean = false) extends DeleteRequest {
    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
     */
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout).delete().map {
        case Successful(_) =>
          logger.info(s"Successfully deleted the version $bucket/$name/$versionId.")

        case response if ignoreExists && response.status == 404 =>
          logger.info(s"Version $bucket/$name/$versionId was not found when deleting. (Success)")

        case response =>
          throw new IllegalStateException(s"Could not delete the version $bucket/$name/$versionId. Response: ${response.status} - ${response.statusText}; ${response.body}")
      }
    }

    def ignoreIfNotExists: DeleteRequest = this.copy(ignoreExists = true)
  }

  def delete: DeleteRequest = WSS3DeleteRequest()

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
   */
  private object RESTGetRequest extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      def req = storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout)

      Source.fromFuture(range.fold(req)(r => req.addHttpHeaders(
        "Range" -> s"bytes=${r.start}-${r.end}")).withMethod("GET").stream().flatMap { response =>
        if (response.status == 200 || response.status == 206) Future.successful(response.bodyAsSource)
        else Future.failed[Source[ByteString, _]](new IllegalStateException(s"Could not get the contents of $bucket/$name/$versionId. Response: ${response.status} - ${response.headers}"))
      }).flatMapMerge(1, x => identity(x))
    }
  }

  def get: GetRequest = RESTGetRequest

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectHEAD.html
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout).
      head().map(_.status == 200)
}
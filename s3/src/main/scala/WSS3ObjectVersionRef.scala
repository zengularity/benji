package com.zengularity.benji.s3

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ VersionedObjectRef, ByteRange }
import com.zengularity.benji.ws.Successful

final case class WSS3VersionedObjectRef(
  storage: WSS3,
  bucket: String,
  name: String,
  versionId: String) extends VersionedObjectRef {

  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  private class WSS3DeleteRequest(
    ignoreExists: Boolean) extends DeleteRequest {

    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectDELETE.html
     */
    def apply()(implicit ec: ExecutionContext): Future[Unit] = {
      storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout).delete().flatMap {
        case Successful(_) => Future.successful(logger.info(s"Successfully deleted the version $bucket/$name/$versionId."))

        case response if ignoreExists && response.status == 404 =>
          Future.successful(logger.info(s"Version $bucket/$name/$versionId was not found when deleting. (Success)"))

        case response =>
          Future.failed[Unit](new IllegalStateException(s"Could not delete the version $bucket/$name/$versionId. Response: ${response.status} - ${response.statusText}; ${response.body}"))
      }
    }

    def ignoreIfNotExists: DeleteRequest = new WSS3DeleteRequest(true)
  }

  def delete: DeleteRequest = new WSS3DeleteRequest(false)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectGET.html
   */
  private object RESTGetRequest extends GetRequest {
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      def req = storage.request(Some(bucket), Some(name), query = Some(s"versionId=$versionId"), requestTimeout = requestTimeout)

      Source.fromFutureSource(range.fold(req)(r => req.addHttpHeaders(
        "Range" -> s"bytes=${r.start}-${r.end}")).withMethod("GET").stream().flatMap { response =>
        if (response.status == 200 || response.status == 206) {
          Future.successful(response.bodyAsSource.
            mapMaterializedValue(_ => NotUsed))
        } else Future.failed[Source[ByteString, NotUsed]](new IllegalStateException(s"Could not get the contents of $bucket/$name/$versionId. Response: ${response.status} - ${response.headers}"))
      }).mapMaterializedValue { _: Future[NotUsed] => NotUsed }
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

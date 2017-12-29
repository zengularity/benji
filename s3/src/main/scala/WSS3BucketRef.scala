package com.zengularity.benji.s3

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.{ BucketRef, Bytes, Object }
import com.zengularity.benji.ws.Successful

final class WSS3BucketRef private[s3] (
  val storage: WSS3,
  val name: String) extends BucketRef[WSS3] { ref =>
  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  object objects extends ref.ListRequest {
    def apply()(implicit m: Materializer, ws: StandaloneAhcWSClient): Source[Object, NotUsed] = {
      S3.getXml[Object](
        storage.request(Some(name), requestTimeout = requestTimeout))({ xml =>
          val contents = xml \ "Contents"

          Source(contents.map { content =>
            Object(
              name = (content \ "Key").text,
              size = Bytes((content \ "Size").text.toLong),
              lastModifiedAt =
                LocalDateTime.parse((content \ "LastModified").text, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
          })
        }, { response => s"Could not list all objects within the bucket $name. Response: ${response.status} - $response" })
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketHEAD.html
   */
  def exists(implicit ec: ExecutionContext, ws: StandaloneAhcWSClient): Future[Boolean] =
    storage.request(Some(name), requestTimeout = requestTimeout).head().map(_.status == 200)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUT.html
   */
  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext, ws: StandaloneAhcWSClient): Future[Boolean] = {
    if (!checkBefore) create.map(_ => true)
    else exists.flatMap {
      case true => Future.successful(false)
      case _ => create.map(_ => true)
    }
  }

  private def create(implicit ec: ExecutionContext, ws: StandaloneAhcWSClient): Future[Unit] = {

    import play.api.libs.ws.DefaultBodyWritables._
    storage.request(Some(name), requestTimeout =
      requestTimeout).put("").map {
      case Successful(_) =>
        logger.info(s"Successfully created the bucket $name.")

      case response =>
        throw new IllegalStateException(s"Could not create the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}")

    }
  }

  private def emptyBucket()(implicit m: Materializer, ws: StandaloneAhcWSClient): Future[Unit] = {
    implicit val ec: ExecutionContext = m.executionContext
    objects().runFoldAsync(())((_: Unit, e) => obj(e.name).delete.ignoreIfNotExists())
  }

  private case class WSS3DeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketDELETE.html
     */
    private def delete()(implicit m: Materializer, tr: Transport): Future[Unit] = {
      implicit val ec = m.executionContext
      storage.request(Some(name), requestTimeout = requestTimeout).delete().map {
        case Successful(_) =>
          logger.info(s"Successfully deleted the bucket $name.")

        case response if ignoreExists && response.status == 404 =>
          logger.info(s"Bucket $name was not found when deleting. (Success)")

        case response =>
          throw new IllegalStateException(s"Could not delete the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}")
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

  def delete: DeleteRequest = WSS3DeleteRequest()

  def obj(objectName: String): WSS3ObjectRef =
    new WSS3ObjectRef(storage, name, objectName)

  override val toString = s"WSS3BucketRef($name)"
}
package com.zengularity.s3

import scala.concurrent.{ ExecutionContext, Future }

import org.joda.time.DateTime

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.http.Status
import play.api.libs.ws.WSClient

import com.zengularity.ws.Successful
import com.zengularity.storage.{ BucketRef, Bytes, Object }

final class WSS3BucketRef private[s3] (
    val storage: WSS3,
    val name: String
) extends BucketRef[WSS3] { ref =>
  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  object objects extends ref.ListRequest {
    def apply()(implicit m: Materializer, ws: WSClient): Source[Object, NotUsed] = {
      S3.getXml[Object](
        storage.request(Some(name), requestTimeout = requestTimeout)
      )({ xml =>
          val contents = xml \ "Contents"

          Source(contents.map { content =>
            Object(
              name = (content \ "Key").text,
              size = Bytes((content \ "Size").text.toLong),
              lastModifiedAt =
                DateTime.parse((content \ "LastModified").text)
            )
          })
        }, { response => s"Could not list all objects within the bucket $name. Response: ${response.status} - $response" })
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketHEAD.html
   */
  def exists(implicit ec: ExecutionContext, ws: WSClient): Future[Boolean] =
    storage.request(Some(name), requestTimeout = requestTimeout).
      head().map(_.status == Status.OK)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUT.html
   */
  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext, ws: WSClient): Future[Boolean] = {
    if (!checkBefore) create.map(_ => true)
    else exists.flatMap {
      case true => Future.successful(false)
      case _    => create.map(_ => true)
    }
  }

  private def create(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] = storage.request(Some(name), requestTimeout = requestTimeout).put("").map {
    case Successful(response) =>
      logger.info(s"Successfully created the bucket $name.")

    case response =>
      throw new IllegalStateException(s"Could not create the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}")

  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketDELETE.html
   */
  def delete()(implicit ec: ExecutionContext, ws: WSClient): Future[Unit] =
    storage.request(Some(name), requestTimeout = requestTimeout).delete().map {
      case Successful(response) =>
        logger.info(s"Successfully deleted the bucket $name.")

      case response =>
        throw new IllegalStateException(s"Could not delete the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}")
    }

  def obj(objectName: String): WSS3ObjectRef =
    new WSS3ObjectRef(storage, name, objectName)

  override val toString = s"WSS3BucketRef($name)"
}

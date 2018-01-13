package com.zengularity.benji.s3

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.StandaloneWSResponse

import com.zengularity.benji.{ BucketRef, Bytes, Object }
import com.zengularity.benji.ws.Successful

final class WSS3BucketRef private[s3] (
  val storage: WSS3,
  val name: String) extends BucketRef { ref =>
  @inline private def logger = storage.logger
  @inline private def requestTimeout = storage.requestTimeout
  //@inline private implicit def ws = storage.transport

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  private case class Objects(maybeMax: Option[Long]) extends ref.ListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def apply()(implicit m: Materializer): Source[Object, NotUsed] = {
      def next(nextToken: String): Source[Object, NotUsed] =
        list(Some(nextToken))(next)

      list(None)(next)
    }

    def list(token: Option[String])(andThen: String => Source[Object, NotUsed])(implicit m: Materializer): Source[Object, NotUsed] = {
      def error(response: StandaloneWSResponse) = s"Could not list all objects within the bucket $name. Response: ${response.status} - ${response.body}"

      // list-type=2 to use AWS v2 for pagination
      val query = maybeMax.map(max => s"?list-type=2&max-keys=$max${token.map(tok => s"&marker=${URLEncoder.encode(tok, "UTF-8")}").getOrElse("")}")

      val request = storage.request(
        Some(name), requestTimeout = requestTimeout, query = query)

      S3.getXml[Object](request)({ xml =>
        val contents = (xml \ "Contents").map(objectFromXml)
        def isTruncated = (xml \ "IsTruncated").text.toBoolean
        val currentPage = Source(contents)

        contents.lastOption.map(_.name) match {
          case Some(nextToken) if isTruncated =>
            currentPage ++ andThen(nextToken)

          case _ => currentPage
        }
      }, error)
    }

    private def objectFromXml(content: scala.xml.Node): Object = {
      Object(
        name = (content \ "Key").text,
        size = Bytes((content \ "Size").text.toLong),
        lastModifiedAt =
          LocalDateTime.parse((content \ "LastModified").text, DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }
  }

  def objects: ListRequest = Objects(None)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketHEAD.html
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean] =
    storage.request(Some(name), requestTimeout = requestTimeout).head().map(_.status == 200)

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketPUT.html
   */
  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = {
    if (!checkBefore) create.map(_ => true)
    else exists.flatMap {
      case true => Future.successful(false)
      case _ => create.map(_ => true)
    }
  }

  private def create(implicit ec: ExecutionContext): Future[Unit] = {

    import play.api.libs.ws.DefaultBodyWritables._
    storage.request(Some(name), requestTimeout =
      requestTimeout).put("").map {
      case Successful(_) =>
        logger.info(s"Successfully created the bucket $name.")

      case response =>
        throw new IllegalStateException(s"Could not create the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}")

    }
  }

  private def emptyBucket()(implicit m: Materializer): Future[Unit] = {
    implicit val ec: ExecutionContext = m.executionContext
    objects().runFoldAsync(())((_: Unit, e) => obj(e.name).delete.ignoreIfNotExists())
  }

  private case class WSS3DeleteRequest(isRecursive: Boolean = false, ignoreExists: Boolean = false) extends DeleteRequest {
    /**
     * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketDELETE.html
     */
    private def delete()(implicit m: Materializer): Future[Unit] = {
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

    def apply()(implicit m: Materializer): Future[Unit] = {
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

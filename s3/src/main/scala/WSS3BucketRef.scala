package com.zengularity.benji.s3

import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.StandaloneWSResponse

import com.zengularity.benji.{ BucketRef, Bytes, Object, BucketVersioning, VersionedObject }
import com.zengularity.benji.ws.Successful

final class WSS3BucketRef private[s3] (
  val storage: WSS3,
  val name: String) extends BucketRef with BucketVersioning { ref =>
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
    objectsVersions().runFoldAsync(())((_: Unit, e) => {
      if (!e.isDeleteMarker) {
        obj(e.name, e.versionId).delete.ignoreIfNotExists()
      } else {
        Future.unit
      }
    })
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

  def versioning: Option[BucketVersioning] = Some(this)

  def isVersioned(implicit ec: ExecutionContext): Future[Boolean] = {
    storage.request(Some(name), requestTimeout = storage.requestTimeout, query = Some("versioning")).get().map(response => {
      val xml = scala.xml.XML.loadString(response.body)
      val status: Option[scala.xml.Node] = xml \ "Status" match {
        case Seq() => None
        case Seq(s) => Some(s)
        case _ => throw new java.io.IOException("Unexpected multiple VersioningConfiguration.Status children from S3")
      }

      status.exists(_.text match {
        case "Enabled" => true
        case "Suspended" => false
        case _ => throw new java.io.IOException("Unexpected VersioningConfiguration.Status content from S3")
      })
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
        val exc = new IllegalStateException(s"Could not change versionning of the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}")
        Future.failed[Unit](exc)
    }
  }

  /**
   * @see http://docs.aws.amazon.com/AmazonS3/latest/API/RESTBucketGET.html
   */
  private case class ObjectsVersions(maybeMax: Option[Long]) extends ref.VersionedListRequest {
    def withBatchSize(max: Long) = this.copy(maybeMax = Some(max))

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = apply(None)

    private def apply(nextToken: Option[String])(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      def error(response: StandaloneWSResponse) = s"Could not list all objects versions within the bucket $name. Response: ${response.status} - ${response.body}"
      val query = maybeMax.map(max => s"versions&max-keys=$max${nextToken.map(token => s"&marker=${URLEncoder.encode(token, "UTF-8")}").getOrElse("")}")
      val request = storage.request(Some(name), requestTimeout = requestTimeout, query = query.orElse(Some("versions")))

      S3.getXml[VersionedObject](request)({ xml =>
        val versions = (xml \ "Version").map(versionFromXml)
        val markers = (xml \ "DeleteMarker").map(deleteMarkerFromXml)
        val versionedObjects = versions ++ markers
        val lastObject = versionedObjects.lastOption
        val isTruncated = (xml \ "IsTruncated").text.toBoolean
        val currentPage = Source(versionedObjects)

        lastObject.map(_.name) match {
          case nextPageToken @ Some(_) if isTruncated => currentPage ++ apply(nextPageToken)
          case _ => currentPage
        }
      }, error)
    }

    private def versionFromXml(content: scala.xml.Node): VersionedObject = {
      VersionedObject(
        name = (content \ "Key").text,
        size = Bytes((content \ "Size").text.toLong),
        versionCreatedAt = LocalDateTime.parse((content \ "LastModified").text, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        versionId = (content \ "VersionId").text,
        isDeleteMarker = false)
    }

    private def deleteMarkerFromXml(content: scala.xml.Node): VersionedObject = {
      VersionedObject(
        name = (content \ "Key").text,
        size = Bytes(0),
        versionCreatedAt = LocalDateTime.parse((content \ "LastModified").text, DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        versionId = (content \ "VersionId").text,
        isDeleteMarker = false)
    }
  }

  def objectsVersions: VersionedListRequest = ObjectsVersions(None)

  def obj(objectName: String, versionId: String): WSS3ObjectVersionRef = WSS3ObjectVersionRef(storage, name, objectName, versionId)
}

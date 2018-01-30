package com.zengularity.benji.s3

import scala.xml.Elem
import scala.collection.immutable.Iterable
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.StandaloneWSResponse

import com.zengularity.benji.{
  BucketRef,
  Object,
  BucketVersioning,
  VersionedObject
}
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

      WSS3BucketRef.list[Object](ref.storage, ref.name, token)(
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
  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean] = {
    if (!checkBefore) createNew.map(_ => true)
    else exists.flatMap {
      case true => Future.successful(false)
      case _ => createNew.map(_ => true)
    }
  }

  private def createNew(implicit ec: ExecutionContext): Future[Unit] = {
    import play.api.libs.ws.DefaultBodyWritables._

    storage.request(Some(name), requestTimeout =
      requestTimeout).put("").flatMap {
      case Successful(_) =>
        Future.successful(logger.info(
          s"Successfully created the bucket $name."))

      case response =>
        Future.failed[Unit](new IllegalStateException(s"Could not create the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}"))

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
      implicit val ec: ExecutionContext = m.executionContext

      storage.request(Some(name), requestTimeout = requestTimeout).
        delete().flatMap {
          case Successful(_) =>
            Future.successful(logger.info(
              s"Successfully deleted the bucket $name."))

          case response if ignoreExists && response.status == 404 =>
            Future.successful(logger.info(
              s"Bucket $name was not found when deleting. (Success)"))

          case response =>
            Future.failed[Unit](new IllegalStateException(s"Could not delete the bucket $name. Response: ${response.status} - ${response.statusText}; ${response.body}"))
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

  def isVersioned(implicit ec: ExecutionContext): Future[Boolean] = for {
    xml <- storage.request(
      Some(name),
      requestTimeout = storage.requestTimeout,
      query = Some("versioning")).get().map { response =>
        scala.xml.XML.loadString(response.body)
      }

    status <- (xml \ "Status") match {
      case Seq(n) if (n.text == "Enabled") => Future.successful(true)
      case Seq(n) if (n.text == "Suspended") => Future.successful(false)
      case Seq() => Future.successful(false)

      case s =>
        Future.failed[Boolean](new IllegalStateException(s"Unexpected multiple VersioningConfiguration.Status children from S3: $s"))
    }
  } yield status

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

    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
      def next(nextToken: String): Source[VersionedObject, NotUsed] =
        list(Some(nextToken))(next(_))

      list(Option.empty[String])(next(_))
    }

    def list(token: Option[String])(andThen: String => Source[VersionedObject, NotUsed])(implicit m: Materializer): Source[VersionedObject, NotUsed] = {
      val parse: Elem => Iterable[VersionedObject] = { xml =>
        val versions = (xml \ "Version").map(Xml.versionDecoder)
        val markers = (xml \ "DeleteMarker").map(Xml.deleteMarkerDecoder)

        versions ++ markers
      }

      val query: Option[String] => Option[String] = { token =>
        buildQuery(versionParam, maxParam(maybeMax), tokenParam(token))
      }

      WSS3BucketRef.list[VersionedObject](ref.storage, ref.name, token)(query, parse, _.name, andThen)
    }

  }

  def objectsVersions: VersionedListRequest = ObjectsVersions(None)

  def obj(objectName: String, versionId: String): WSS3ObjectVersionRef = WSS3ObjectVersionRef(storage, name, objectName, versionId)
}

object WSS3BucketRef {
  /**
   * @param name the bucket name
   * @param token the continuation token
   */
  private[s3] def list[T](storage: WSS3, name: String, token: Option[String])(
    query: Option[String] => Option[String],
    parse: Elem => Iterable[T],
    marker: T => String,
    andThen: String => Source[T, NotUsed])(implicit m: Materializer): Source[T, NotUsed] = {
    @inline def requestTimeout = storage.requestTimeout

    def error(response: StandaloneWSResponse) = s"Could not list objects within the bucket $name. Response: ${response.status} - ${response.body}"

    val request = storage.request(
      Some(name), requestTimeout = requestTimeout, query = query(token))

    S3.getXml[T](request)({ xml =>
      def isTruncated = (xml \ "IsTruncated").text.toBoolean

      val parsed = parse(xml)
      def currentPage = Source(parsed)

      parsed.lastOption.map(marker) match {
        case Some(tok) if isTruncated =>
          currentPage ++ andThen(tok)

        case _ => currentPage
      }
    }, error)
  }
}

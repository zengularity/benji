/*
 * Copyright (C) 2018-2018 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString

import play.api.libs.ws.BodyWritable

/**
 * A live reference to a storage object.
 * The operations are scoped on the specified object.
 *
 * Such reference must only be used with the storage which resolved it first.
 *
 * @define putSizeParam the total size in bytes to be PUTed
 * @define thresholdParam the multipart threshold
 * @define consumerInputTParam the consumer input type
 * @define consumerOutputTparam the consumer output type
 * @define moveToOperation Moves the referenced object to another one. If fails, the current object is still available.
 * @define copyToOperation Copies the referenced object to another one.
 * @define targetParam the reference to the target object
 * @define preventOverwriteParam if true, prevents overwriting an existing target object
 * @define targetBucketNameParam the name of the parent bucket for the target object
 * @define targetObjectNameParam the name of the target object
 */
trait ObjectRef { ref =>
  /**
   * The name of parent bucket.
   */
  def bucket: String

  /**
   * The name of the object itself.
   */
  def name: String

  /**
   * The default threshold for multipart (multi-component) upload.
   */
  def defaultThreshold: Bytes

  /**
   * Checks whether or not this object exists.
   * May also return `false` in cases you don't have permission
   * to view a certain object.
   *
   * {{{
   * myObject.exists
   * }}}
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Returns the headers associated with the currently referenced object.
   *
   * {{{
   * myObject.headers()
   * }}}
   */
  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Returns the metadata associated with the currently referenced object
   * (normalized from the `headers`).
   *
   * {{{
   * myObject.metadata()
   * }}}
   */
  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Prepares the request to get the contents of this object.
   *
   * {{{
   * myObject.get()
   * }}}
   */
  def get: GetRequest

  /**
   * @tparam E $consumerInputTParam
   */
  def put[E](implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[NotUsed]] = put[E, NotUsed](NotUsed)((_, _) => Future.successful(NotUsed))

  /**
   * @tparam E $consumerInputTParam
   * @param size $putSizeParam
   */
  def put[E](size: Long)(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[NotUsed]] = put[E, NotUsed](NotUsed, size = Some(size))((_, _) => Future.successful(NotUsed))

  /**
   * @tparam E $consumerInputTParam
   * @tparam A $consumerOutputTparam
   *
   * {{{
   * def upload[T <: ObjectStorage[_]](obj: ObjectRef[T], data: ByteString) =
   *   obj.put[ByteString, Unit](NotUsed)((_, _) => Future.successful(NotUsed))
   * }}}
   */
  def put[E, A]: PutRequest[E, A]

  /**
   * Prepares a request to delete the referenced object.
   *
   * {{{
   * myObject.delete()
   * }}}
   */
  def delete: DeleteRequest

  /**
   * $moveToOperation
   *
   * @param target $targetParam
   * @param preventOverwrite $preventOverwriteParam (default: true)
   *
   * {{{
   * myObject.moveTo(otherRef)
   * }}}
   */
  final def moveTo(target: ObjectRef, preventOverwrite: Boolean = true)(implicit ec: ExecutionContext): Future[Unit] = target match {
    case ObjectRef(targetBucketName, targetObjectName) =>
      moveTo(targetBucketName, targetObjectName, preventOverwrite)

    case _ => Future.failed[Unit](new IllegalArgumentException(
      s"Target object you specified [$target] is unknown."))
  }

  /**
   * $moveToOperation
   *
   * @param targetBucketName $targetBucketNameParam
   * @param targetObjectName $targetObjectNameParam
   * @param preventOverwrite $preventOverwriteParam
   *
   * {{{
   * myObject.moveTo("targetBucket", "newName")
   * }}}
   */
  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext): Future[Unit]

  /**
   * $copyToOperation
   *
   * @param target $targetParam
   *
   * {{{
   * myObject.copyTo(otherRef)
   * }}}
   */
  def copyTo(target: ObjectRef)(implicit ec: ExecutionContext): Future[Unit] = target match {
    case ObjectRef(targetBucketName, targetObjectName) =>
      copyTo(targetBucketName, targetObjectName)

    case _ => Future.failed[Unit](new IllegalArgumentException(
      s"Target object you specified [$target] is unknown."))
  }

  /**
   * $copyToOperation
   *
   * @param targetBucketName $targetBucketNameParam
   * @param targetObjectName $targetObjectNameParam
   *
   * {{{
   * myObject.copyTo("targetBucket", "copyName")
   * }}}
   */
  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext): Future[Unit]

  /**
   * Try to get a reference of this object that would allow
   * you to perform versioning related operations.
   *
   * @return Some if the module of the object supports versioning
   * related operations, otherwise None.
   *
   * {{{
   * myObject.versioning.map(_.isVersioned)
   * }}}
   */
  def versioning: Option[ObjectVersioning]

  // ---

  /** A GET request. */
  trait GetRequest {

    /**
     * Retrieves the contents of this object
     *
     * @param range the optional request range
     *
     * {{{
     * myObject.get()
     * }}}
     */
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed]
  }

  /**
   * A PUT request: allows you to update the contents of this object.
   *
   * @tparam E $consumerInputTParam
   * @tparam A $consumerOutputTparam
   */
  trait PutRequest[E, A] {

    @deprecated("Use `apply` with metadata", "1.3.1")
    final def apply(z: => A, threshold: Bytes, size: Option[Long])(f: (A, Chunk) => Future[A])(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[A]] = apply(z, threshold, size, Map.empty[String, String])(f)

    /**
     * Applies this request to the specified object.
     *
     * @param threshold $thresholdParam
     * @param size $putSizeParam
     * @param metadata the object metadata
     *
     * {{{
     * val put = myObject.put[Array[Byte], Long]
     * val upload = put(0L, metadata = Map("foo" -> "bar")) { (sz, chunk) =>
     *   Future.successful(sz + chunk.size)
     * }
     * }}}
     */
    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None, metadata: Map[String, String] = Map.empty[String, String])(f: (A, Chunk) => Future[A])(implicit m: Materializer, w: BodyWritable[E]): Sink[E, Future[A]]
  }

  /**
   * A request to delete the bucket.
   */
  trait DeleteRequest {
    /**
     * Deletes the current object.
     *
     * {{{
     * myObject.delete()
     * }}}
     */
    def apply()(implicit ec: ExecutionContext): Future[Unit]

    /**
     * Updates the request, so that it will not raise an error
     * if the referenced object doesn't exist when executed.
     *
     * {{{
     * myObject.delete.ignoreIfNotExists()
     * }}}
     */
    def ignoreIfNotExists: DeleteRequest
  }
}

/**
 * Companion object
 */
object ObjectRef {
  /**
   * Extractor.
   *
   * {{{
   * def description(r: ObjectRef): String = r match {
   *   case Object(bucket, objName) => s"\$bucket -> \$objName"
   * }
   * }}}
   */
  def unapply(ref: ObjectRef): Option[(String, String)] = Some(ref.bucket -> ref.name)
}

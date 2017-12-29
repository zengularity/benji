package com.zengularity.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString

/**
 * A object reference.
 * The operations are scoped on the specified object.
 *
 * @tparam T the type of object storage
 * @define putSizeParam the total size in bytes to be PUTed
 * @define transportParam the storage transport
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
trait ObjectRef[T <: ObjectStorage[T]] { ref =>
  /** The type of the storage transport. */
  final type Transport = T#Pack#Transport

  /** The type of writer usable with the storage. */
  final type Writer[A] = T#Pack#Writer[A]

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
   * Determines whether or not this object exists.
   * `false` might be returned also in cases where you don't have permission
   * to view a certain object.
   *
   * @param tr $transportParam
   */
  def exists(implicit ec: ExecutionContext, tr: Transport): Future[Boolean]

  /**
   * Returns the headers associated with the currently referenced object.
   *
   * @param tr $transportParam
   */
  def headers()(implicit ec: ExecutionContext, tr: Transport): Future[Map[String, Seq[String]]]

  /**
   * Prepares the request to get the contents of this object.
   */
  def get: GetRequest

  /**
   * @tparam E $consumerInputTParam
   * @param tr $transportParam
   */
  def put[E](implicit m: Materializer, tr: Transport, w: Writer[E]): Sink[E, Future[NotUsed]] = put[E, NotUsed](NotUsed.getInstance)((_, _) => Future.successful(NotUsed.getInstance))

  /**
   * @tparam E $consumerInputTParam
   * @param size $putSizeParam
   * @param tr $transportParam
   */
  def put[E](size: Long)(implicit m: Materializer, tr: Transport, w: Writer[E]): Sink[E, Future[NotUsed]] = put[E, NotUsed](NotUsed.getInstance, size = Some(size))((_, _) => Future.successful(NotUsed.getInstance))

  /**
   * @tparam E $consumerInputTParam
   * @tparam A $consumerOutputTparam
   *
   * {{{
   * def upload[T <: ObjectStorage[_]](obj: ObjectRef[T], data: ByteString) =
   *   obj.put[ByteString, Unit](NotUsed.getInstance)((_, _) => Future.successful(NotUsed.getInstance))
   * }}}
   */
  def put[E, A]: PutRequest[E, A]

  trait DeleteRequest {
    /**
     * Deletes the current object
     */
    def apply()(implicit ec: ExecutionContext, tr: Transport): Future[Unit]

    /**
     * Updates the request, so that it will not raise an error if the referenced object doesn't exist when executed
     */
    def ignoreIfNotExists: DeleteRequest
  }

  /**
   * Prepares a request to delete the referenced objecg
   */
  def delete: DeleteRequest

  /**
   * $moveToOperation
   *
   * @param target $targetParam
   * @param preventOverwrite $preventOverwriteParam (default: true)
   * @param tr $transportParam
   */
  def moveTo(target: T#ObjectRef, preventOverwrite: Boolean = true)(implicit ec: ExecutionContext, tr: Transport): Future[Unit] = target match {
    case ObjectRef(targetBucketName, targetObjectName) =>
      moveTo(targetBucketName, targetObjectName, preventOverwrite)

    case _ =>
      throw new IllegalArgumentException(
        s"Target object you specified [$target] is unknown.")
  }

  /**
   * $moveToOperation
   *
   * @param targetBucketName $targetBucketNameParam
   * @param targetObjectName $targetObjectNameParam
   * @param preventOverwrite $preventOverwriteParam
   * @param tr $transportParam
   */
  def moveTo(targetBucketName: String, targetObjectName: String, preventOverwrite: Boolean)(implicit ec: ExecutionContext, tr: Transport): Future[Unit]

  /**
   * $copyToOperation
   *
   * @param target $targetParam
   * @param tr $transportParam
   */
  def copyTo(target: T#ObjectRef)(implicit ec: ExecutionContext, tr: Transport): Future[Unit] = target match {
    case ObjectRef(targetBucketName, targetObjectName) =>
      copyTo(targetBucketName, targetObjectName)

    case _ =>
      throw new IllegalArgumentException(
        s"Target object you specified [$target] is unknown.")
  }

  /**
   * $copyToOperation
   *
   * @param targetBucketName $targetBucketNameParam
   * @param targetObjectName $targetObjectNameParam
   * @param tr $transportParam
   */
  def copyTo(targetBucketName: String, targetObjectName: String)(implicit ec: ExecutionContext, tr: Transport): Future[Unit]

  /** A GET request. */
  trait GetRequest {

    /**
     * Retrieves the contents of this object
     *
     * @param range the optional request range
     * @param tr $transportParam
     */
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer, tr: Transport): Source[ByteString, NotUsed]
  }

  /**
   * A PUT request: allows you to update the contents of this object.
   *
   * @tparam E $consumerInputTParam
   * @tparam A $consumerOutputTparam
   */
  trait PutRequest[E, A] {
    @deprecated("Use [[apply]] with metadata", "1.3.1")
    final def apply(z: => A, threshold: Bytes, size: Option[Long])(f: (A, Chunk) => Future[A])(implicit m: Materializer, ws: Transport, w: Writer[E]): Sink[E, Future[A]] = apply(z, threshold, size, Map.empty)(f)

    /**
     * Applies this request to the specified object.
     *
     * @param threshold $thresholdParam
     * @param size $putSizeParam
     * @param metadata the object metadata
     * @param tr $transportParam
     */
    def apply(z: => A, threshold: Bytes = defaultThreshold, size: Option[Long] = None, metadata: Map[String, String] = Map.empty)(f: (A, Chunk) => Future[A])(implicit m: Materializer, tr: Transport, w: Writer[E]): Sink[E, Future[A]]
  }
}

/**
 * Companion object
 */
object ObjectRef {
  /**
   * {{{
   * def description[T <: Storage](r: ObjectRef[T]): String = r match {
   *   case Object(bucket, objName) => s"\$bucket -> \$objName"
   * }
   * }}}
   */
  def unapply[T <: ObjectStorage[T]](ref: ObjectRef[T]): Option[(String, String)] = Some(ref.bucket -> ref.name)
}

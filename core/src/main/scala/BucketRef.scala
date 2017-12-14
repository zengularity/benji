package com.zengularity.benji

import scala.language.higherKinds

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

/**
 * A bucket reference.
 * The operations are scoped on the specified bucket.
 *
 * @tparam T the type of object storage
 * @define transportParam the storage transport
 */
trait BucketRef[T <: ObjectStorage[T]] {

  /** The type of the storage transport. */
  final type Transport = T#Pack#Transport

  /**
   * The name of the bucket.
   */
  def name: String

  /**
   * A request to list the objects inside this bucket.
   */
  trait ListRequest {
    /**
     * Lists of all objects within the bucket.
     *
     * @param tr $transportParam
     */
    def apply()(implicit m: Materializer, tr: Transport): Source[Object, NotUsed]

    /**
     * Collects the bucket objects.
     */
    def collect[M[_]]()(implicit m: Materializer, tr: Transport, builder: CanBuildFrom[M[_], Object, M[Object]]): Future[M[Object]] = {
      implicit def ec: ExecutionContext = m.executionContext

      apply() runWith Sink.fold(builder()) {
        _ += (_: Object)
      }.mapMaterializedValue(_.map(_.result()))
    }
    // TODO: Support a max
  }

  /**
   * Prepares a request to list the bucket objects.
   *
   * {{{
   * def enumObjects[T <: ObjectStorage[_]](b: BucketRef[T]) = b.objects()
   *
   * def objectList[T <: ObjectStorage[_]](b: BucketRef[T]) =
   *   b.objects.collect[List]()
   * }}}
   */
  def objects: ListRequest

  /**
   * Determines whether or not the bucket exists.
   * `false` might be returned also in cases where you don't have permission
   * to view a certain bucket.
   *
   * @param tr $transportParam
   *
   * {{{
   * def check[T <: ObjectStorage[T]](store: T, name: String)(implicit ec: ExecutionContext, tr: T#Pack#Transport): Future[Boolean] = store.bucket(name).exists
   * }}}
   */
  def exists(implicit ec: ExecutionContext, tr: Transport): Future[Boolean]

  /**
   * Creates the bucket.
   *
   * @param checkBefore if true, checks if it already exists before
   * @param tr $transportParam
   * @return true if a new bucket has been created, false if skipped
   *
   * {{{
   * def setupBucket[T <: ObjectStorage[T]](store: T, name: String)(implicit ec: ExecutionContext, tr: T#Pack#Transport): Future[BucketRef[T]] = {
   *   // Make sure a bucket is available (either a new or existing one)
   *   val bucket = store.bucket(name)
   *   bucket.create().map {
   *     case true => bucket // newly created bucket
   *     case false => bucket // existing bucket
   *   }
   * }
   * }}}
   */
  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext, tr: Transport): Future[Boolean]

  /**
   * Deletes this empty bucket, or fails if the bucket is not empty.
   *
   * @param tr $transportParam
   */
  def delete()(implicit m: Materializer, tr: Transport): Future[Unit]

  /**
   * Deletes this bucket.
   *
   * @param recursive If false the operation will fail when the bucket is not empty.
   * @param tr $transportParam
   */
  def delete(recursive: Boolean)(implicit m: Materializer, tr: Transport): Future[Unit]

  /**
   * Returns a reference to an child object, specified by the given name.
   *
   * @param objectName the name of child object
   */
  def obj(objectName: String): T#ObjectRef
}

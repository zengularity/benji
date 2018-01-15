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
 * Such reference must only be used with the storage which resolved it first.
 */
trait BucketRef {
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
     */
    def apply()(implicit m: Materializer): Source[Object, NotUsed]

    /**
     * Collects the bucket objects.
     */
    final def collect[M[_]]()(implicit m: Materializer, builder: CanBuildFrom[M[_], Object, M[Object]]): Future[M[Object]] = {
      implicit def ec: ExecutionContext = m.executionContext

      apply() runWith Sink.fold(builder()) {
        _ += (_: Object)
      }.mapMaterializedValue(_.map(_.result()))
    }

    /**
     * Define batch size for retrieving objects with multiple requests
     * @param max the batch size, indicating the maximum number of objects fetch at once
     */
    def withBatchSize(max: Long): ListRequest
  }

  /**
   * Prepares a request to list the bucket objects.
   *
   * {{{
   * def enumObjects(b: BucketRef) = b.objects()
   *
   * def objectList(b: BucketRef) = b.objects.collect[List]()
   * }}}
   */
  def objects: ListRequest

  /**
   * Determines whether or not the bucket exists.
   * `false` might be returned also in cases where you don't have permission
   * to view a certain bucket.
   *
   * {{{
   * def check(store: ObjectStorage, name: String)(implicit ec: ExecutionContext): Future[Boolean] = store.bucket(name).exists
   * }}}
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Creates the bucket.
   *
   * @param checkBefore if true, checks if it already exists before
   * @return true if a new bucket has been created, false if skipped
   *
   * {{{
   * def setupBucket(store: ObjectStorage, name: String)(implicit ec: ExecutionContext): Future[BucketRef] = {
   *   // Make sure a bucket is available (either a new or existing one)
   *   val bucket = store.bucket(name)
   *   bucket.create().map {
   *     case true => bucket // newly created bucket
   *     case false => bucket // existing bucket
   *   }
   * }
   * }}}
   */
  def create(checkBefore: Boolean = false)(implicit ec: ExecutionContext): Future[Boolean]

  trait DeleteRequest {
    /**
     * Deletes the current bucket
     */
    def apply()(implicit m: Materializer): Future[Unit]

    /**
     * Updates the request, so that it will not raise an error if the referenced bucket doesn't exist when executed
     */
    def ignoreIfNotExists: DeleteRequest

    /**
     * Updates the request so that it will succeed on non-empty bucket, by also deleting all of its content,
     * this includes its objects and if applicable also all versions of the objects.
     */
    def recursive: DeleteRequest
  }

  /**
   * Prepares a request to delete the referenced bucket
   */
  def delete: DeleteRequest

  /**
   * Returns a reference to an child object, specified by the given name.
   *
   * @param objectName the name of child object
   */
  def obj(objectName: String): ObjectRef

  /**
   * Try to get a reference of this bucket that would allow you to perform versioning related operations.
   *
   * @return Some if the module of the bucket supports versioning related operations, otherwise None.
   */
  def versioning: Option[BucketVersioning]
}

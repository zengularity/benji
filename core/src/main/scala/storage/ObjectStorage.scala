package com.zengularity.storage

import scala.collection.generic.CanBuildFrom

import scala.concurrent.{ ExecutionContext, Future }

import play.api.libs.iteratee.{ Enumerator, Iteratee }

/**
 * Common API for Object storage.
 *
 * @define bucketNameParam the name of the bucket
 */
trait ObjectStorage[T <: ObjectStorage[T]] { self =>
  /**
   * The type of transport package,
   * required by an underlying implementation.
   */
  type Pack <: StoragePack with Singleton

  /**
   * The type for the object references managed by this storage.
   */
  type ObjectRef <: com.zengularity.storage.ObjectRef[T]

  /** Storage logger */
  val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  /**
   * Returns a WS/S3 instance with specified request timeout.
   *
   * @param timeout the request timeout in milliseconds
   */
  def withRequestTimeout(timeout: Long): T

  /**
   * A request to list the buckets.
   */
  trait BucketsRequest {
    /**
     * Lists of all objects within the bucket.
     *
     * @param tr the storage transport
     */
    def apply()(implicit ec: ExecutionContext, tr: Pack#Transport): Enumerator[Bucket]

    /**
     * Collects the bucket objects.
     */
    def collect[M[_]]()(implicit ec: ExecutionContext, tr: Pack#Transport, builder: CanBuildFrom[M[_], Bucket, M[Bucket]]): Future[M[Bucket]] = (apply() |>>> Iteratee.fold(builder()) { _ += (_: Bucket) }).map(_.result())
    // TODO: Support a max
  }

  /**
   * Prepares the request to list the buckets.
   *
   * {{{
   * def enumBuckets[T <: ObjectStorage[_]](store: T) = ls.buckets()
   *
   * def bucketSet[T <: ObjectStorage[_]](store: T) = ls.buckets.collect[Set]()
   * }}}
   */
  def buckets: BucketsRequest

  /**
   * Returns a reference to a bucket specified by its name.
   *
   * @param name $bucketNameParam
   */
  def bucket(name: String): BucketRef[T]
}

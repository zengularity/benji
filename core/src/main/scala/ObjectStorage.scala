package com.zengularity.benji

import scala.language.higherKinds

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

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
  type ObjectRef <: com.zengularity.benji.ObjectRef[T]

  /** Storage logger */
  val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  /**
   * Returns a storage instance with specified request timeout.
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
    def apply()(implicit m: Materializer, tr: Pack#Transport): Source[Bucket, NotUsed]

    /**
     * Collects the bucket objects.
     */
    def collect[M[_]]()(implicit m: Materializer, tr: Pack#Transport, builder: CanBuildFrom[M[_], Bucket, M[Bucket]]): Future[M[Bucket]] = {
      implicit def ec: ExecutionContext = m.executionContext

      apply() runWith Sink.fold(builder()) {
        _ += (_: Bucket)
      }.mapMaterializedValue(_.map(_.result()))
    }
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

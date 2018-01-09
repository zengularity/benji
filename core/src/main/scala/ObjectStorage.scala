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
trait ObjectStorage { self =>
  /** Storage logger */
  val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  /**
   * Returns a storage instance with specified request timeout.
   *
   * @param timeout the request timeout in milliseconds
   */
  def withRequestTimeout(timeout: Long): ObjectStorage

  /**
   * A request to list the buckets.
   */
  trait BucketsRequest {
    /**
     * Lists of all objects within the bucket.
     */
    def apply()(implicit m: Materializer): Source[Bucket, NotUsed]

    /**
     * Collects the bucket objects.
     */
    def collect[M[_]]()(implicit m: Materializer, builder: CanBuildFrom[M[_], Bucket, M[Bucket]]): Future[M[Bucket]] = {
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
   * def enumBuckets(store: ObjectStorage) = ls.buckets()
   *
   * def bucketSet(store: ObjectStorage) = ls.buckets.collect[Set]()
   * }}}
   */
  def buckets: BucketsRequest

  /**
   * Returns a reference to a bucket specified by its name.
   *
   * @param name $bucketNameParam
   */
  def bucket(name: String): BucketRef
}

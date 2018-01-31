package com.zengularity.benji

import scala.language.higherKinds

import scala.collection.generic.CanBuildFrom
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

/**
 * Represents a reference to a bucket that supports versioning.
 */
trait BucketVersioning {
  /**
   * Checks whether the versioning is currently enabled or not on this bucket.
   *
   * @return A future with true if versioning is currently enabled, otherwise a future with false.
   */
  def isVersioned(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Enables or disables the versioning of objects on this bucket,
   * existing versions history will not be erased when versioning is disabled.
   */
  def setVersioning(enabled: Boolean)(implicit ec: ExecutionContext): Future[Unit]

  trait VersionedListRequest {
    /**
     * Lists of all versioned objects within the bucket.
     */
    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed]

    /**
     * Collects the bucket objects.
     */
    final def collect[M[_]]()(implicit m: Materializer, builder: CanBuildFrom[M[_], VersionedObject, M[VersionedObject]]): Future[M[VersionedObject]] = {
      implicit def ec: ExecutionContext = m.executionContext

      apply() runWith Sink.fold(builder()) {
        _ += (_: VersionedObject)
      }.mapMaterializedValue(_.map(_.result()))
    }

    /**
     * Define batch size for retrieving objects with multiple requests
     * @param max the batch size, indicating the maximum number of objects fetch at once
     */
    def withBatchSize(max: Long): VersionedListRequest
  }

  /**
   * Prepares a request to list the bucket versioned objects.
   */
  def objectsVersions: VersionedListRequest

  /**
   * Gets a reference to a specific version of an object, allowing you to perform operations on an object version.
   */
  def obj(objectName: String, versionId: String): VersionedObjectRef
}

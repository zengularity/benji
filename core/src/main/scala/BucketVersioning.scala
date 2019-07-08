/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import scala.language.higherKinds

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
   * @return A future with true if versioning is currently enabled,
   * otherwise a future with false.
   *
   * {{{
   * versioning.isVersioned
   * }}}
   */
  def isVersioned(implicit ec: ExecutionContext): Future[Boolean]

  /**
   * Enables or disables the versioning of objects on this bucket,
   * existing versions history will not be erased when versioning is disabled.
   *
   * {{{
   * versioning.setVersioning(true)
   * }}}
   */
  def setVersioning(enabled: Boolean)(implicit ec: ExecutionContext): Future[Unit]

  /**
   * Prepares a request to list the bucket versioned objects.
   *
   * {{{
   * versioning.versionedObjects()
   * }}}
   */
  def versionedObjects: VersionedListRequest

  /**
   * Gets a reference to a specific version of an object,
   * allowing you to perform operations on an object version.
   *
   * {{{
   * versioning.obj("objInBucket", "1.0")
   * }}}
   */
  def obj(objectName: String, versionId: String): VersionedObjectRef

  /**
   * Prepares a request to list the bucket objects.
   */
  trait VersionedListRequest {
    /**
     * Lists of the matching versioned objects within the bucket.
     *
     * {{{
     * versioning.versionedObjects()
     * }}}
     */
    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed]

    /**
     * Collects the matching objects.
     *
     * {{{
     * versioning.versionedObjects.collect[List]()
     * }}}
     */
    final def collect[M[_]]()(implicit m: Materializer, @deprecatedName(Symbol("builder")) factory: Compat.Factory[M, VersionedObject]): Future[M[VersionedObject]] = {
      implicit def ec: ExecutionContext = m.executionContext

      apply() runWith Sink.fold(
        Compat.newBuilder[M, VersionedObject](factory)) {
          _ += (_: VersionedObject)
        }.mapMaterializedValue(_.map(_.result()))
    }

    /**
     * Defines batch size for retrieving objects with multiple requests.
     *
     * @param max the maximum number of objects fetch at once
     *
     * {{{
     * versioning.versionedObjects.withBatchSize(10L).collect[Set]()
     * }}}
     */
    def withBatchSize(max: Long): VersionedListRequest

    /**
     * Defines the prefix the listed objects must match.
     *
     * {{{
     * versioning.versionedObjects.withPrefix("foo").collect[Set]()
     * }}}
     */
    def withPrefix(prefix: String): VersionedListRequest
  }
}

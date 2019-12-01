/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

/**
 * Operations that are supported by a versioned object.
 *
 * @see [[VersionedObjectRef]]
 */
trait ObjectVersioning {
  /**
   * Prepares a request to list the bucket versioned objects.
   *
   * {{{
   * import akka.stream.Materializer
   * import com.zengularity.benji.ObjectVersioning
   *
   * def foo(versioning: ObjectVersioning)(implicit m: Materializer) =
   *   versioning.versions() // versions.apply()
   *
   * def bar(versioning: ObjectVersioning)(implicit m: Materializer) =
   *   versioning.versions.collect[Set]()
   * }}}
   */
  def versions: VersionedListRequest

  /**
   * Gets a reference to a specific version of an object,
   * allowing you to perform operations on an object version.
   *
   * {{{
   * import scala.concurrent.ExecutionContext
   * import com.zengularity.benji.ObjectVersioning
   *
   * def foo(versioning: ObjectVersioning)(implicit ec: ExecutionContext) =
   *   versioning.version("1.0").exists
   * }}}
   */
  def version(versionId: String): VersionedObjectRef

  // ---

  /**
   * Prepares a request to list the bucket objects.
   */
  trait VersionedListRequest {
    /**
     * Lists of all versioned objects within the bucket.
     *
     * {{{
     * import akka.stream.Materializer
     * import com.zengularity.benji.ObjectVersioning
     *
     * def foo(versioning: ObjectVersioning)(implicit m: Materializer) =
     *   versioning.versions()
     * }}}
     */
    def apply()(implicit m: Materializer): Source[VersionedObject, NotUsed]

    /**
     * Collects the bucket objects.
     *
     * {{{
     * import akka.stream.Materializer
     * import com.zengularity.benji.ObjectVersioning
     *
     * def foo(versioning: ObjectVersioning)(implicit m: Materializer) =
     *   versioning.versions.collect[List]()
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
     * Define batch size for retrieving objects with multiple requests
     * @param max the maximum number of objects fetch at once
     */
    def withBatchSize(max: Long): VersionedListRequest
  }
}

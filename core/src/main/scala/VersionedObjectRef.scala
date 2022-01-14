/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.Source

/**
 * A live reference to a versioned object.
 * The operations are scoped on a specific object version.
 *
 * Such reference must only be used with the storage which resolved it first.
 *
 * @see [[ObjectRef]]
 */
trait VersionedObjectRef {

  /**
   * The name of parent bucket.
   */
  def bucket: String

  /**
   * The name of the object itself.
   */
  def name: String

  /**
   * The versionId of this reference.
   */
  def versionId: String

  /**
   * Prepares a request to delete the referenced object
   *
   * {{{
   * import akka.stream.Materializer
   * import com.zengularity.benji.VersionedObjectRef
   *
   * def foo(myObject: VersionedObjectRef)(implicit m: Materializer) =
   *   myObject.delete()
   * }}}
   */
  def delete: DeleteRequest

  /**
   * Prepares the request to get the contents of this specific version.
   *
   * {{{
   * import akka.stream.Materializer
   * import com.zengularity.benji.VersionedObjectRef
   *
   * def foo(myObject: VersionedObjectRef)(implicit m: Materializer) =
   *   myObject.get()
   * }}}
   */
  def get: GetRequest

  /**
   * Returns the headers of the referenced version.
   *
   * {{{
   * import scala.concurrent.ExecutionContext
   * import com.zengularity.benji.VersionedObjectRef
   *
   * def foo(myObject: VersionedObjectRef)(implicit ec: ExecutionContext) =
   *   myObject.headers()
   * }}}
   */
  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Returns the metadata of the referenced version.
   * (normalized from the `headers`).
   *
   * {{{
   * import scala.concurrent.ExecutionContext
   * import com.zengularity.benji.VersionedObjectRef
   *
   * def foo(myObject: VersionedObjectRef)(implicit ec: ExecutionContext) =
   *   myObject.metadata()
   * }}}
   */
  def metadata(
    )(implicit
      ec: ExecutionContext
    ): Future[Map[String, Seq[String]]]

  /**
   * Checks whether or not this object exists.
   * May also return `false` in cases you don't have permission
   * to view a certain object.
   *
   * {{{
   * import scala.concurrent.ExecutionContext
   * import com.zengularity.benji.VersionedObjectRef
   *
   * def foo(myObject: VersionedObjectRef)(implicit ec: ExecutionContext) =
   *   myObject.exists
   * }}}
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean]

  // ---

  /** A GET request. */
  trait GetRequest {

    /**
     * Retrieves the contents of this object.
     *
     * @param range the optional request range
     *
     * {{{
     * import akka.stream.Materializer
     * import com.zengularity.benji.VersionedObjectRef
     *
     * def foo(myObject: VersionedObjectRef)(implicit m: Materializer) =
     *   myObject.get()
     * }}}
     */
    def apply(
        range: Option[ByteRange] = None
      )(implicit
        m: Materializer
      ): Source[ByteString, NotUsed]
  }

  /**
   * A request to delete the bucket.
   */
  trait DeleteRequest {

    /**
     * Deletes the current object.
     *
     * {{{
     * import akka.stream.Materializer
     * import com.zengularity.benji.VersionedObjectRef
     *
     * def foo(myObject: VersionedObjectRef)(implicit m: Materializer) =
     *   myObject.delete()
     * }}}
     */
    def apply()(implicit m: Materializer): Future[Unit]

    /**
     * Updates the request, so that it will not raise an error
     * if the referenced object doesn't exist when executed.
     *
     * {{{
     * import akka.stream.Materializer
     * import com.zengularity.benji.VersionedObjectRef
     *
     * def foo(myObject: VersionedObjectRef)(implicit m: Materializer) =
     *   myObject.delete.ignoreIfNotExists()
     * }}}
     */
    def ignoreIfNotExists: DeleteRequest
  }
}

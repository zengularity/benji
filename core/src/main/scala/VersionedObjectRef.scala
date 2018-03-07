/*
 * Copyright (C) 2018-2018 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString

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
   * myObject.delete()
   * }}}
   */
  def delete: DeleteRequest

  /**
   * Prepares the request to get the contents of this specific version.
   *
   * {{{
   * myObject.get()
   * }}}
   */
  def get: GetRequest

  /**
   * Returns the headers of the referenced version.
   *
   * {{{
   * myObject.headers()
   * }}}
   */
  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Returns the metadata of the referenced version.
   * (normalized from the `headers`).
   *
   * {{{
   * myObject.metadata()
   * }}}
   */
  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Checks whether or not this object exists.
   * May also return `false` in cases you don't have permission
   * to view a certain object.
   *
   * {{{
   * myObject.exists
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
     * myObject.get()
     * }}}
     */
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed]
  }

  /**
   * A request to delete the bucket.
   */
  trait DeleteRequest {
    /**
     * Deletes the current object.
     *
     * {{{
     * myObject.delete()
     * }}}
     */
    def apply()(implicit m: Materializer): Future[Unit]

    /**
     * Updates the request, so that it will not raise an error
     * if the referenced object doesn't exist when executed.
     *
     * {{{
     * myObject.delete.ignoreIfNotExists()
     * }}}
     */
    def ignoreIfNotExists: DeleteRequest
  }
}

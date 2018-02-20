package com.zengularity.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString

/**
 * An object version reference.
 * The operations are scoped on a specific version.
 *
 * Such reference must only be used with the storage which resolved it first.
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

  trait DeleteRequest {
    /**
     * Deletes the current object
     */
    def apply()(implicit m: Materializer): Future[Unit]

    /**
     * Updates the request, so that it will not raise an error if the referenced object doesn't exist when executed
     */
    def ignoreIfNotExists: DeleteRequest
  }

  /**
   * Prepares a request to delete the referenced object
   */
  def delete: DeleteRequest

  /** A GET request. */
  trait GetRequest {

    /**
     * Retrieves the contents of this object
     *
     * @param range the optional request range
     */
    def apply(range: Option[ByteRange] = None)(implicit m: Materializer): Source[ByteString, NotUsed]
  }

  /**
   * Prepares the request to get the contents of this specific version.
   */
  def get: GetRequest

  /**
   * Returns the headers of the referenced version.
   */
  def headers()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Returns the metadata of the referenced version.
   */
  def metadata()(implicit ec: ExecutionContext): Future[Map[String, Seq[String]]]

  /**
   * Determines whether or not this version exists.
   * `false` might be returned also in cases where you don't have permission
   * to view a certain object.
   */
  def exists(implicit ec: ExecutionContext): Future[Boolean]
}

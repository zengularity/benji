/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ BucketRef, BucketVersioning, Object, ObjectRef }

/**
 * GridFS bucket reference.
 *
 * @param transport the GridFS transport
 * @param name the name of the bucket (GridFS collection name)
 */
final class GridFSBucketRef(
    @SuppressWarnings(Array("org.wartremover.warts.UnusedMethodParameter"))
    transport: GridFSTransport,
    val name: String)
    extends BucketRef {

  def obj(objectName: String): ObjectRef =
    new GridFSObjectRef(transport, name, objectName)

  def versioning: Option[BucketVersioning] = None

  def exists(
      implicit
      ec: ExecutionContext
    ): Future[Boolean] =
    Future.successful(true) // TODO: Implement

  def create(
      failsIfExists: Boolean = false
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] =
    Future.successful(()) // TODO: Implement

  def delete: DeleteRequest = GridFSDeleteRequest()

  def objects: ListRequest = GridFSListRequest()

  def removeAll(
      prefix: Option[String] = None,
      batchSize: Option[Int] = None
    )(implicit
      ec: ExecutionContext
    ): Future[Long] =
    Future.successful(0L) // TODO: Implement

  // DeleteRequest implementation
  private case class GridFSDeleteRequest(
      ignoreExists: Boolean = false,
      isRecursive: Boolean = false)
      extends DeleteRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Future[Unit] =
      Future.successful(()) // TODO: Implement

    def ignoreIfNotExists: DeleteRequest =
      GridFSDeleteRequest(ignoreExists = true, isRecursive)

    def recursive: DeleteRequest =
      GridFSDeleteRequest(ignoreExists, isRecursive = true)
  }

  // ListRequest implementation
  private case class GridFSListRequest(
      prefix: Option[String] = None,
      batchSize: Option[Long] = None)
      extends ListRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Source[Object, NotUsed] =
      Source.empty[Object] // TODO: Implement

    def withBatchSize(max: Long): ListRequest =
      GridFSListRequest(prefix, Some(max))

    def withPrefix(p: String): ListRequest =
      GridFSListRequest(Some(p), batchSize)
  }
}

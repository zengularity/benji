/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ BucketRef, BucketVersioning, Object, ObjectRef }
import com.zengularity.benji.exception.BucketAlreadyExistsException

/**
 * GridFS bucket reference.
 *
 * @param transport the GridFS transport
 * @param name the name of the bucket (GridFS collection name)
 */
final class GridFSBucketRef(
    val transport: GridFSTransport,
    val name: String)
    extends BucketRef {

  def obj(objectName: String): ObjectRef =
    new GridFSObjectRef(transport, name, objectName)

  def versioning: Option[BucketVersioning] = None

  def exists(
      implicit
      ec: ExecutionContext
    ): Future[Boolean] =
    transport.getDatabase.flatMap(checkExists)

  private def checkExists(
      db: reactivemongo.api.DB
    )(implicit
      ec: ExecutionContext
    ): Future[Boolean] =
    db.collectionNames.map { _.contains(name) }

  def create(
      failsIfExists: Boolean = false
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] =
    transport.getDatabase.flatMap { db =>
      if (failsIfExists) createWithCheck(db)
      else Future.successful(())
    }

  private def createWithCheck(
      db: reactivemongo.api.DB
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] =
    db.collectionNames.flatMap { names =>
      if (names contains name) {
        Future.failed(new BucketAlreadyExistsException(name))
      } else {
        Future.successful(())
      }
    }

  def delete: DeleteRequest = GridFSDeleteRequest()

  def objects: ListRequest = GridFSListRequest()

  def removeAll(
      prefix: Option[String] = None,
      batchSize: Option[Int] = None
    )(implicit
      ec: ExecutionContext
    ): Future[Long] = {
    val _ = (prefix, batchSize, ec)

    Future.successful(0L)
  }

  // DeleteRequest implementation
  final private case class GridFSDeleteRequest(
      ignoreExists: Boolean = false,
      isRecursive: Boolean = false)
      extends DeleteRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Future[Unit] = {
      implicit val ec: ExecutionContext = m.executionContext

      transport.getDatabase.flatMap { db =>
        db.drop().recover {
          case _ if ignoreExists => ()
        }
      }
    }

    def ignoreIfNotExists: DeleteRequest =
      GridFSDeleteRequest(ignoreExists = true, isRecursive)

    def recursive: DeleteRequest =
      GridFSDeleteRequest(ignoreExists, isRecursive = true)
  }

  // ListRequest implementation
  final private case class GridFSListRequest(
      prefix: Option[String] = None,
      batchSize: Option[Long] = None)
      extends ListRequest {

    def apply(
      )(implicit
        m: Materializer
      ): Source[Object, NotUsed] =
      Source.empty[Object]

    def withBatchSize(max: Long): ListRequest =
      GridFSListRequest(prefix, Some(max))

    def withPrefix(p: String): ListRequest =
      GridFSListRequest(Some(p), batchSize)
  }
}

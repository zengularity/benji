/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString

import play.api.libs.ws.BodyWritable

import com.zengularity.benji.{
  Bytes,
  ByteRange,
  Chunk,
  ObjectRef,
  ObjectVersioning
}

/**
 * GridFS object reference.
 *
 * @param transport the GridFS transport
 * @param bucket the bucket (collection) name
 * @param name the object name
 */
final class GridFSObjectRef(
    val transport: GridFSTransport,
    val bucket: String,
    val name: String)
    extends ObjectRef {

  def defaultThreshold: Bytes =
    Bytes(1024L * 1024L * 5L) // 5 MB

  def versioning: Option[ObjectVersioning] = None

  def exists(
      implicit
      ec: ExecutionContext
    ): Future[Boolean] =
    Future.successful(false) // TODO: Query GridFS

  def headers(
    )(implicit
      ec: ExecutionContext
    ): Future[Map[String, Seq[String]]] =
    Future.successful(Map.empty)

  def metadata(
    )(implicit
      ec: ExecutionContext
    ): Future[Map[String, Seq[String]]] =
    Future.successful(Map.empty)

  def get: GetRequest = GridFSGetRequest()

  def put[E, A]: PutRequest[E, A] = new GridFSPutRequest[E, A]()

  def delete: DeleteRequest = GridFSDeleteRequest()

  def moveTo(
      targetBucketName: String,
      targetObjectName: String,
      preventOverwrite: Boolean
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] = {
    copyTo(targetBucketName, targetObjectName).flatMap { _ =>
      delete().flatMap(_ => Future.successful(()))
    }
  }

  def copyTo(
      targetBucketName: String,
      targetObjectName: String
    )(implicit
      ec: ExecutionContext
    ): Future[Unit] =
    Future.successful(())

  override def toString: String =
    s"GridFSObjectRef($bucket, $name)"

  // GetRequest implementation
  private case class GridFSGetRequest(
      range: Option[ByteRange] = None)
      extends GetRequest {

    def apply(
        range: Option[ByteRange] = None
      )(implicit
        m: Materializer
      ): Source[ByteString, NotUsed] =
      Source.empty[ByteString]
  }

  // PutRequest implementation
  private final class GridFSPutRequest[E, A]() extends PutRequest[E, A] {

    def apply(
        z: => A,
        threshold: Bytes = defaultThreshold,
        size: Option[Long] = None,
        metadata: Map[String, String]
      )(f: (A, Chunk) => Future[A]
      )(implicit
        m: Materializer,
        w: BodyWritable[E]
      ): Sink[E, Future[A]] = {
      implicit val ec: ExecutionContext = m.executionContext
      // Return a sink that ignores input and returns the initial value
      Sink.ignore.mapMaterializedValue { _ => Future.successful(z) }
    }
  }

  // DeleteRequest implementation
  private case class GridFSDeleteRequest(
      ignoreExists: Boolean = false)
      extends DeleteRequest {

    def apply(
      )(implicit
        ec: ExecutionContext
      ): Future[Unit] =
      Future.successful(())

    def ignoreIfNotExists: DeleteRequest =
      GridFSDeleteRequest(ignoreExists = true)
  }
}

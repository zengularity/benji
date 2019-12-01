/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.exception

import java.io.IOException

import com.zengularity.benji.BucketRef

/** An error while using an object storage. */
abstract class BenjiException extends IOException

/** An unknown error */
case class BenjiUnknownError(
  message: String,
  throwable: Option[Throwable] = None) extends BenjiException {
  override def getMessage: String =
    throwable.map(t => s"$message - ${t.getMessage}").getOrElse(message)
}

/**
 * An error when an operation cannot be applied on a not empty bucket
 * (e.g. delete).
 */
case class BucketNotEmptyException(bucketName: String) extends BenjiException {
  override def getMessage: String = s"Bucket '$bucketName' was not empty."
}

object BucketNotEmptyException {
  private[benji] def apply(bucket: BucketRef): BucketNotEmptyException =
    BucketNotEmptyException(bucket.name)
}

/**
 * An error when an operation is refused for an already existing bucket.
 *
 * {{{
 * import scala.concurrent.ExecutionContext
 *
 * import com.zengularity.benji.ObjectStorage
 * import com.zengularity.benji.exception.BucketAlreadyExistsException
 *
 * def foo(
 *   storage: ObjectStorage,
 *   bucketName: String)(implicit ec: ExecutionContext) =
 *   storage.bucket(bucketName).create(failsIfExists = true).map { _ =>
 *     println(s"\$bucketName created")
 *   }.recover {
 *     case BucketAlreadyExistsException(_) =>
 *       println(s"\$bucketName already exists")
 *   }
 * }}}
 */
case class BucketAlreadyExistsException(
  bucketName: String) extends BenjiException {
  override def getMessage: String = s"Bucket '$bucketName' already exists."
}

object BucketAlreadyExistsException {
  private[benji] def apply(bucket: BucketRef): BucketAlreadyExistsException =
    BucketAlreadyExistsException(bucket.name)
}

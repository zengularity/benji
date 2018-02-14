package com.zengularity.benji.exception

import java.io.IOException

import com.zengularity.benji.BucketRef

abstract class BenjiException extends IOException

case class BenjiUnknownError(message: String, throwable: Option[Throwable] = None) extends BenjiException {
  override def getMessage: String = throwable.map(t => s"$message - ${t.getMessage}").getOrElse(message)
}

case class BucketNotEmptyException(bucketName: String) extends BenjiException {
  override def getMessage: String = s"Bucket '$bucketName' was not empty."
}

object BucketNotEmptyException {
  def apply(bucket: BucketRef): BucketNotEmptyException = BucketNotEmptyException(bucket.name)
}

case class BucketAlreadyExistsException(bucketName: String) extends BenjiException {
  override def getMessage: String = s"Bucket '$bucketName' already exists."
}

object BucketAlreadyExistsException {
  def apply(bucket: BucketRef): BucketAlreadyExistsException = BucketAlreadyExistsException(bucket.name)
}

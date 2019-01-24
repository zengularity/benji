/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.exception

import com.zengularity.benji.{ BucketRef, ObjectRef, VersionedObjectRef }

/** An error when either a bucket or an object is not found. */
abstract class EntityNotFoundException extends BenjiException

/** An error when a bucket is not found. */
case class BucketNotFoundException(
  bucketName: String) extends EntityNotFoundException {
  override def getMessage: String = s"Bucket '$bucketName' not found."
}

object BucketNotFoundException {
  private[benji] def apply(bucket: BucketRef): BucketNotFoundException =
    BucketNotFoundException(bucket.name)
}

/** An error when an object is not found. */
case class ObjectNotFoundException(
  bucketName: String, objectName: String) extends EntityNotFoundException {
  override def getMessage: String = s"Object '$objectName' not found inside bucket '$bucketName'."
}

object ObjectNotFoundException {
  private[benji] def apply(obj: ObjectRef): ObjectNotFoundException =
    ObjectNotFoundException(obj.bucket, obj.name)
}

/** An error when an object version is not found. */
case class VersionNotFoundException(
  bucketName: String,
  objectName: String,
  versionId: String) extends EntityNotFoundException {
  override def getMessage: String = s"Version '$versionId' of object '$objectName' not found inside bucket '$bucketName'."
}

object VersionNotFoundException {
  private[benji] def apply(
    version: VersionedObjectRef): VersionNotFoundException =
    VersionNotFoundException(version.bucket, version.name, version.versionId)
}

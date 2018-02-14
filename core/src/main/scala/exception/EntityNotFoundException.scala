package com.zengularity.benji.exception

import com.zengularity.benji.{ BucketRef, ObjectRef, VersionedObjectRef }

abstract class EntityNotFoundException extends BenjiException

case class BucketNotFoundException(bucketName: String) extends EntityNotFoundException {
  override def getMessage: String = s"Bucket '$bucketName' not found."
}

object BucketNotFoundException {
  def apply(bucket: BucketRef): BucketNotFoundException = BucketNotFoundException(bucket.name)
}

case class ObjectNotFoundException(bucketName: String, objectName: String) extends EntityNotFoundException {
  override def getMessage: String = s"Object '$objectName' not found inside bucket '$bucketName'."
}

object ObjectNotFoundException {
  def apply(obj: ObjectRef): ObjectNotFoundException = ObjectNotFoundException(obj.bucket, obj.name)
}

case class VersionNotFoundException(bucketName: String, objectName: String, versionId: String) extends EntityNotFoundException {
  override def getMessage: String = s"Version '$versionId' of object '$objectName' not found inside bucket '$bucketName'."
}

object VersionNotFoundException {
  def apply(version: VersionedObjectRef): VersionNotFoundException = VersionNotFoundException(version.bucket, version.name, version.versionId)
}
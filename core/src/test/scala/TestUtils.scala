package com.zengularity.benji.tests

import com.zengularity.benji.{ BucketRef, ObjectRef, VersionedObjectRef }

import com.zengularity.benji.exception.{
  BucketNotFoundException,
  BucketNotEmptyException,
  BucketAlreadyExistsException,
  ObjectNotFoundException,
  VersionNotFoundException
}

object TestUtils {
  def bucketNotEmpty(bucket: BucketRef): BucketNotEmptyException =
    BucketNotEmptyException(bucket)

  def bucketAlreadyExists(bucket: BucketRef): BucketAlreadyExistsException =
    BucketAlreadyExistsException(bucket)

  def versionNotFound(ref: VersionedObjectRef): VersionNotFoundException =
    VersionNotFoundException(ref)

  def bucketNotFound(bucket: BucketRef): BucketNotFoundException =
    BucketNotFoundException(bucket)

  def objectNotFound(ref: ObjectRef): ObjectNotFoundException =
    ObjectNotFoundException(ref)
}

/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.s3

import play.api.libs.ws.StandaloneWSResponse

import com.zengularity.benji.exception.{
  BenjiUnknownError,
  BucketAlreadyExistsException,
  BucketNotEmptyException,
  BucketNotFoundException,
  ObjectNotFoundException,
  VersionNotFoundException
}

private[s3] object ErrorHandler {

  def ofBucket(
      defaultMessage: => String,
      bucketName: String
    )(response: StandaloneWSResponse
    ): Throwable = {
    import response.body

    response.status match {
      case 404 if body.contains("<Code>NoSuchBucket</Code>") =>
        BucketNotFoundException(bucketName)

      case 409 if body.contains("<Code>BucketAlreadyOwnedByYou</Code>") =>
        BucketAlreadyExistsException(bucketName)

      case 409 if body.contains("<Code>BucketNotEmpty</Code>") =>
        BucketNotEmptyException(bucketName)

      case status =>
        BenjiUnknownError(
          s"$defaultMessage - Response: ${status.toString} - $body"
        )
    }
  }

  def ofBucket(
      defaultMessage: => String,
      bucket: WSS3BucketRef
    )(response: StandaloneWSResponse
    ): Throwable =
    ofBucket(defaultMessage, bucket.name)(response)

  def ofObject(
      defaultMessage: => String,
      bucketName: String,
      objName: String
    )(response: StandaloneWSResponse
    ): Throwable = {
    import response.body
    response.status match {
      case 404
          if body.contains("<Code>NoSuchBucket</Code>")
            || body.contains("<Code>NoSuchKey</Code>")
            || body.isEmpty =>
        ObjectNotFoundException(bucketName, objName)

      case status =>
        BenjiUnknownError(
          s"$defaultMessage - Response: ${status.toString} - $body"
        )
    }
  }

  def ofObject(
      defaultMessage: => String,
      obj: WSS3ObjectRef
    )(response: StandaloneWSResponse
    ): Throwable =
    ofObject(defaultMessage, obj.bucket, obj.name)(response)

  def ofVersion(
      defaultMessage: => String,
      bucketName: String,
      objName: String,
      versionId: String
    )(response: StandaloneWSResponse
    ): Throwable = {
    import response.body

    response.status match {
      case 404
          if body.contains("<Code>NoSuchBucket</Code>")
            || body.contains("<Code>NoSuchKey</Code>")
            || body.contains("<Code>NoSuchVersion</Code>")
            || body.isEmpty =>
        VersionNotFoundException(bucketName, objName, versionId)
      case status =>
        BenjiUnknownError(
          s"$defaultMessage - Response: ${status.toString} - $body"
        )
    }
  }

  def ofVersion(
      defaultMessage: => String,
      version: WSS3VersionedObjectRef
    )(response: StandaloneWSResponse
    ): Throwable =
    ofVersion(defaultMessage, version.bucket, version.name, version.versionId)(
      response
    )
}

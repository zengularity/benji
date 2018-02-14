package com.zengularity.benji.s3

import com.zengularity.benji.exception.{
  BenjiUnknownError,
  BucketAlreadyExistsException,
  BucketNotFoundException,
  ObjectNotFoundException,
  VersionNotFoundException,
  BucketNotEmptyException
}

import play.api.libs.ws.StandaloneWSResponse

private[s3] object ErrorHandler {
  def ofBucket(defaultMessage: => String, bucketName: String)(response: StandaloneWSResponse): Throwable = (response.status, response.body) match {
    case (404, body) if body.contains("<Code>NoSuchBucket</Code>") => BucketNotFoundException(bucketName)
    case (409, body) if body.contains("<Code>BucketAlreadyOwnedByYou</Code>") => BucketAlreadyExistsException(bucketName)
    case (409, body) if body.contains("<Code>BucketNotEmpty</Code>") => BucketNotEmptyException(bucketName)
    case (status, body) => BenjiUnknownError(s"$defaultMessage - Response: $status - $body")
  }

  def ofBucket(defaultMessage: => String, bucket: WSS3BucketRef)(response: StandaloneWSResponse): Throwable =
    ofBucket(defaultMessage, bucket.name)(response)

  def ofObject(defaultMessage: => String, bucketName: String, objName: String)(response: StandaloneWSResponse): Throwable = (response.status, response.body) match {
    case (404, body) if body.contains("<Code>NoSuchBucket</Code>")
      || body.contains("<Code>NoSuchKey</Code>")
      || body.isEmpty => ObjectNotFoundException(bucketName, objName)
    case (status, body) => BenjiUnknownError(s"$defaultMessage - Response: $status - $body")
  }

  def ofObject(defaultMessage: => String, obj: WSS3ObjectRef)(response: StandaloneWSResponse): Throwable =
    ofObject(defaultMessage, obj.bucket, obj.name)(response)

  def ofVersion(defaultMessage: => String, bucketName: String, objName: String, versionId: String)(response: StandaloneWSResponse): Throwable = (response.status, response.body) match {
    case (404, body) if body.contains("<Code>NoSuchBucket</Code>")
      || body.contains("<Code>NoSuchKey</Code>")
      || body.contains("<Code>NoSuchVersion</Code>")
      || body.isEmpty => VersionNotFoundException(bucketName, objName, versionId)
    case (status, body) => BenjiUnknownError(s"$defaultMessage - Response: $status - $body")
  }

  def ofVersion(defaultMessage: => String, version: WSS3VersionedObjectRef)(response: StandaloneWSResponse): Throwable =
    ofVersion(defaultMessage, version.bucket, version.name, version.versionId)(response)
}

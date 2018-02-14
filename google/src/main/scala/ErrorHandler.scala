package com.zengularity.benji.google

import scala.concurrent.Future

import com.google.api.client.googleapis.json.GoogleJsonResponseException

import play.api.libs.json.{ Json, JsDefined, JsUndefined, JsString }
import play.api.libs.ws.StandaloneWSResponse

import com.zengularity.benji.exception.{
  BenjiException,
  BenjiUnknownError,
  BucketAlreadyExistsException,
  BucketNotFoundException,
  ObjectNotFoundException,
  VersionNotFoundException,
  BucketNotEmptyException
}

private[google] object ErrorHandler {
  def ofBucketFromValues(defaultMessage: => String, bucketName: String)(statusCode: Int, message: String): Throwable =
    (statusCode, message) match {
      case (404, _) => BucketNotFoundException(bucketName)
      case (409, "You already own this bucket. Please select another name.") => BucketAlreadyExistsException(bucketName)
      case (409, "The bucket you tried to delete was not empty.") => BucketNotEmptyException(bucketName)
      case (_, _) => BenjiUnknownError(s"$defaultMessage - Response: $statusCode - $message")
    }

  def ofBucketFromValues(defaultMessage: => String, bucket: GoogleBucketRef)(statusCode: Int, message: String): Throwable =
    ofBucketFromValues(defaultMessage, bucket.name)(statusCode, message)

  def ofBucketFromResponse[T](defaultMessage: => String, bucketName: String)(response: StandaloneWSResponse): Future[T] = {
    val json = Json.parse(response.body)
    json \ "error" \ "message" match {
      case JsDefined(JsString(msg)) =>
        Future.failed[T](ofBucketFromValues(defaultMessage, bucketName)(response.status, msg))
      case e: JsUndefined =>
        Future.failed[T](new java.io.IOException(s"$defaultMessage: Could not parse error json ${e.error} in $json"))
      case JsDefined(j) =>
        Future.failed[T](new java.io.IOException(s"$defaultMessage: Could not parse error json unexpected message value $j in $json"))
    }
  }

  def ofBucketFromResponse[T](defaultMessage: => String, bucket: GoogleBucketRef)(response: StandaloneWSResponse): Future[T] =
    ofBucketFromResponse[T](defaultMessage, bucket.name)(response)

  def ofBucket(defaultMessage: => String, bucketName: String): Throwable => Throwable = {
    case g: GoogleJsonResponseException => ofBucketFromValues(defaultMessage, bucketName)(g.getStatusCode, g.getDetails.getMessage)
    case b: BenjiException => b
    case error => BenjiUnknownError(s"$defaultMessage - ${error.getMessage}", Some(error))
  }

  def ofBucket(defaultMessage: => String, bucket: GoogleBucketRef): Throwable => Throwable =
    ofBucket(defaultMessage, bucket.name)

  def ofBucketToFuture[T](defaultMessage: => String, bucketName: String): PartialFunction[Throwable, Future[T]] = {
    case error => Future.failed[T](ofBucket(defaultMessage, bucketName)(error))
  }

  def ofBucketToFuture[T](defaultMessage: => String, bucket: GoogleBucketRef): PartialFunction[Throwable, Future[T]] =
    ofBucketToFuture[T](defaultMessage, bucket.name)

  def ofObject(defaultMessage: => String, bucketName: String, objName: String): Throwable => Throwable = {
    case g: GoogleJsonResponseException => (g.getStatusCode, g.getStatusMessage) match {
      case (404, _) => ObjectNotFoundException(bucketName, objName)
      case (code, message) => BenjiUnknownError(s"$defaultMessage - Response: $code - $message")
    }
    case b: BenjiException => b
    case error => BenjiUnknownError(s"$defaultMessage - ${error.getMessage}", Some(error))
  }

  def ofObject(defaultMessage: => String, obj: GoogleObjectRef): Throwable => Throwable =
    ofObject(defaultMessage, obj.bucket, obj.name)

  def ofObjectToFuture[T](defaultMessage: => String, bucketName: String, objName: String): PartialFunction[Throwable, Future[T]] = {
    case error => Future.failed[T](ofObject(defaultMessage, bucketName, objName)(error))
  }

  def ofObjectToFuture[T](defaultMessage: => String, obj: GoogleObjectRef): PartialFunction[Throwable, Future[T]] =
    ofObjectToFuture[T](defaultMessage, obj.bucket, obj.name)

  def ofVersion(defaultMessage: => String, bucketName: String, objName: String, versionId: String): Throwable => Throwable = {
    case g: GoogleJsonResponseException => (g.getStatusCode, g.getStatusMessage) match {
      case (404, _) => VersionNotFoundException(bucketName, objName, versionId)
      case (code, message) => BenjiUnknownError(s"$defaultMessage - Response: $code - $message")
    }
    case b: BenjiException => b
    case error => BenjiUnknownError(s"$defaultMessage - ${error.getMessage}", Some(error))
  }

  def ofVersion(defaultMessage: => String, version: GoogleVersionedObjectRef): Throwable => Throwable =
    ofVersion(defaultMessage, version.bucket, version.name, version.versionId)

  def ofVersionToFuture[T](defaultMessage: => String, bucketName: String, objName: String, versionId: String): PartialFunction[Throwable, Future[T]] = {
    case error => Future.failed[T](ofVersion(defaultMessage, bucketName, objName, versionId)(error))
  }

  def ofVersionToFuture[T](defaultMessage: => String, version: GoogleVersionedObjectRef): PartialFunction[Throwable, Future[T]] =
    ofVersionToFuture[T](defaultMessage, version.bucket, version.name, version.versionId)
}

/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import scala.concurrent.Future

import play.api.libs.json.{ JsDefined, JsString, JsUndefined, Json }
import play.api.libs.ws.StandaloneWSResponse

import com.google.api.client.googleapis.json.GoogleJsonResponseException

import com.zengularity.benji.exception.{
  BenjiException,
  BenjiUnknownError,
  BucketAlreadyExistsException,
  BucketNotEmptyException,
  BucketNotFoundException,
  ObjectNotFoundException,
  VersionNotFoundException
}

private[google] object ErrorHandler {

  def ofBucketFromValues(
      defaultMessage: => String,
      bucketName: String
    )(statusCode: Int,
      message: String
    ): Throwable =
    (statusCode, message) match {
      case (404, _) => BucketNotFoundException(bucketName)

      case (
            409,
            "Your previous request to create the named bucket succeeded and you already own it."
          ) =>
        BucketAlreadyExistsException(bucketName)

      case (409, "The bucket you tried to delete is not empty.") =>
        BucketNotEmptyException(bucketName)

      case (_, _) =>
        BenjiUnknownError(
          s"$defaultMessage - Response: ${statusCode.toString} - $message"
        )
    }

  def ofBucketFromValues(
      defaultMessage: => String,
      bucket: GoogleBucketRef
    )(statusCode: Int,
      message: String
    ): Throwable =
    ofBucketFromValues(defaultMessage, bucket.name)(statusCode, message)

  def ofBucketFromResponse[T](
      defaultMessage: => String,
      bucketName: String
    )(response: StandaloneWSResponse
    ): Future[T] = {
    val json = Json.parse(response.body)

    json \ "error" \ "message" match {
      case JsDefined(JsString(msg)) =>
        Future.failed[T](
          ofBucketFromValues(defaultMessage, bucketName)(response.status, msg)
        )

      case e: JsUndefined =>
        Future.failed[T](
          new java.io.IOException(
            s"$defaultMessage: Could not parse error json ${e.error} in ${Json stringify json}"
          )
        )

      case JsDefined(j) =>
        Future.failed[T](
          new java.io.IOException(
            s"$defaultMessage: Could not parse error json unexpected message value ${Json stringify j} in ${Json stringify json}"
          )
        )
    }
  }

  def ofBucketFromResponse[T](
      defaultMessage: => String,
      bucket: GoogleBucketRef
    )(response: StandaloneWSResponse
    ): Future[T] =
    ofBucketFromResponse[T](defaultMessage, bucket.name)(response)

  def ofBucket(
      defaultMessage: => String,
      bucketName: String
    ): Throwable => Throwable = {
    case g: GoogleJsonResponseException => {
      val msg = {
        if (g.getDetails == null) g.getMessage
        else g.getDetails.getMessage
      }

      ofBucketFromValues(defaultMessage, bucketName)(g.getStatusCode, msg)
    }

    case b: BenjiException => b

    case error =>
      BenjiUnknownError(s"$defaultMessage - ${error.getMessage}", Some(error))
  }

  def ofBucket(
      defaultMessage: => String,
      bucket: GoogleBucketRef
    ): Throwable => Throwable =
    ofBucket(defaultMessage, bucket.name)

  def ofBucketToFuture[T](
      defaultMessage: => String,
      bucketName: String
    ): PartialFunction[Throwable, Future[T]] = {
    case error => Future.failed[T](ofBucket(defaultMessage, bucketName)(error))
  }

  def ofBucketToFuture[T](
      defaultMessage: => String,
      bucket: GoogleBucketRef
    ): PartialFunction[Throwable, Future[T]] =
    ofBucketToFuture[T](defaultMessage, bucket.name)

  def ofObject(
      defaultMessage: => String,
      bucketName: String,
      objName: String
    ): Throwable => Throwable = {
    case g: GoogleJsonResponseException =>
      g.getStatusCode match {
        case 404 =>
          ObjectNotFoundException(bucketName, objName)

        case code =>
          BenjiUnknownError(s"$defaultMessage - Response: ${code.toString} - ${g.getStatusMessage}")
      }

    case b: BenjiException => b

    case error =>
      BenjiUnknownError(s"$defaultMessage - ${error.getMessage}", Some(error))
  }

  def ofObject(
      defaultMessage: => String,
      obj: GoogleObjectRef
    ): Throwable => Throwable =
    ofObject(defaultMessage, obj.bucket, obj.name)

  def ofObjectToFuture[T](
      defaultMessage: => String,
      bucketName: String,
      objName: String
    ): PartialFunction[Throwable, Future[T]] = {
    case error =>
      Future.failed[T](ofObject(defaultMessage, bucketName, objName)(error))
  }

  def ofObjectToFuture[T](
      defaultMessage: => String,
      obj: GoogleObjectRef
    ): PartialFunction[Throwable, Future[T]] =
    ofObjectToFuture[T](defaultMessage, obj.bucket, obj.name)

  def ofVersion(
      defaultMessage: => String,
      bucketName: String,
      objName: String,
      versionId: String
    ): Throwable => Throwable = {
    case g: GoogleJsonResponseException =>
      (g.getStatusCode, g.getStatusMessage) match {
        case (404, _) =>
          VersionNotFoundException(bucketName, objName, versionId)

        case (code, message) =>
          BenjiUnknownError(
            s"$defaultMessage - Response: ${code.toString} - $message"
          )
      }

    case b: BenjiException => b

    case error =>
      BenjiUnknownError(s"$defaultMessage - ${error.getMessage}", Some(error))
  }

  def ofVersion(
      defaultMessage: => String,
      version: GoogleVersionedObjectRef
    ): Throwable => Throwable =
    ofVersion(defaultMessage, version.bucket, version.name, version.versionId)

  def ofVersionToFuture[T](
      defaultMessage: => String,
      bucketName: String,
      objName: String,
      versionId: String
    ): PartialFunction[Throwable, Future[T]] = {
    case error =>
      Future.failed[T](
        ofVersion(defaultMessage, bucketName, objName, versionId)(error)
      )
  }

  def ofVersionToFuture[T](
      defaultMessage: => String,
      version: GoogleVersionedObjectRef
    ): PartialFunction[Throwable, Future[T]] =
    ofVersionToFuture[T](
      defaultMessage,
      version.bucket,
      version.name,
      version.versionId
    )
}

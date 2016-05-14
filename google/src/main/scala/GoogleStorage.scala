package com.zengularity.google

import org.joda.time.DateTime

import scala.concurrent.{ ExecutionContext, Future }

import play.api.libs.iteratee.Enumerator

import com.zengularity.storage.{ Bucket, ObjectStorage }

/**
 * Implementation of the Google API for Cloud Storage.
 *
 * @define requestTimeoutParam the optional timeout for the prepared requests
 * @define disableGZipParam if true, disables the GZip compression for upload and download
 * @param requestTimeout $requestTimeoutParam
 * @param disableGZip $disableGZipParam (automatically disabled for multi-part upload)
 */
class GoogleStorage(
    val requestTimeout: Option[Long],
    val disableGZip: Boolean
) extends ObjectStorage[GoogleStorage] { self =>
  import scala.collection.JavaConversions.collectionAsScalaIterable

  type Pack = GoogleStoragePack.type
  type ObjectRef = GoogleObjectRef

  def withRequestTimeout(timeout: Long) =
    new GoogleStorage(Some(timeout), disableGZip)

  /**
   * Returns a new instance with GZip compression disabled.
   */
  def withDisabledGZip(disabled: Boolean) =
    new GoogleStorage(requestTimeout, disabled)

  def bucket(name: String) = new GoogleBucketRef(this, name)

  object buckets extends self.BucketsRequest {
    def apply()(implicit ec: ExecutionContext, gt: GoogleTransport): Enumerator[Bucket] = Enumerator.flatten(Future {
      val items = gt.buckets().list(gt.projectId).execute().getItems

      Enumerator.enumerate(
        if (items == null) List.empty[Bucket] else items.map { b =>
          Bucket(b.getName, new DateTime(b.getTimeCreated.getValue))
        }.toList
      )
    })

    // TODO: Use pagination
  }
}

/** Google factory. */
object GoogleStorage {
  /**
   * Returns a client for Google Cloud Storage.
   *
   * @param requestTimeout $requestTimeoutParam (none by default)
   * @param disableGZip $disableGZipParam (default: false)
   */
  def apply(requestTimeout: Option[Long] = None, disableGZip: Boolean = false): GoogleStorage = new GoogleStorage(requestTimeout, disableGZip)
}

/**
 * {{{
 * def check(err: Throwable) = err match {
 *   case HttpResponse(code, message) => ???
 * }
 * }}}
 */
object HttpResponse {
  import com.google.api.client.googleapis.json.GoogleJsonResponseException

  def unapply(err: Throwable): Option[(Int, String)] = err match {
    case g: GoogleJsonResponseException =>
      Some(g.getStatusCode -> g.getStatusMessage)

    case _ => None
  }
}

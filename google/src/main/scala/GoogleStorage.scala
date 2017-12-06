package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ Bucket, ObjectStorage }

/**
 * Implementation of the Google API for Cloud Storage.
 *
 * @param requestTimeout the optional timeout for the prepared requests
 * @param disableGZip if true, disables the GZip compression for upload and download (automatically disabled for multi-part upload)
 */
class GoogleStorage(
  val requestTimeout: Option[Long],
  val disableGZip: Boolean) extends ObjectStorage[GoogleStorage] { self =>
  import scala.collection.JavaConverters._

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
    def apply()(implicit m: Materializer, gt: GoogleTransport): Source[Bucket, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source.fromFuture(Future {
        val items = gt.buckets().list(gt.projectId).execute().getItems

        Source(
          if (items == null) List.empty[Bucket] else items.asScala.map { b =>
            Bucket(b.getName, LocalDateTime.ofInstant(Instant.ofEpochMilli(b.getTimeCreated.getValue), ZoneOffset.UTC))
          }.toList)
      }).flatMapMerge(1, identity)
    }

    // TODO: Use pagination
  }
}

/** Google factory. */
object GoogleStorage {
  /**
   * Returns a client for Google Cloud Storage.
   *
   * @param requestTimeout the optional timeout for the prepared requests (none by default)
   * @param disableGZip if true, disables the GZip compression for upload and download (default: false)
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

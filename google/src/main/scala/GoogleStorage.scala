/*
 * Copyright (C) 2018-2022 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.google

import java.time.{ Instant, LocalDateTime, ZoneOffset }

import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ Bucket, Compat, ObjectStorage }

/**
 * Implementation of the Google API for Cloud Storage.
 *
 * @param requestTimeout the optional timeout for the prepared requests
 * @param disableGZip if true, disables the GZip compression for upload and download (automatically disabled for multi-part upload)
 */
class GoogleStorage(
    private[google] val transport: GoogleTransport,
    val requestTimeout: Option[Long],
    val disableGZip: Boolean)
    extends ObjectStorage { self =>

  import Compat.javaConverters._

  def withRequestTimeout(timeout: Long): GoogleStorage =
    new GoogleStorage(transport, Some(timeout), disableGZip)

  /**
   * Returns a new instance with GZip compression disabled.
   */
  def withDisabledGZip(disabled: Boolean): GoogleStorage =
    new GoogleStorage(transport, requestTimeout, disabled)

  def bucket(name: String) = new GoogleBucketRef(this, name)

  object buckets extends self.BucketsRequest {

    @com.github.ghik.silencer.silent(".*fromFuture.*")
    def apply()(implicit m: Materializer): Source[Bucket, NotUsed] = {
      implicit def ec: ExecutionContext = m.executionContext

      Source
        .fromFuture(Future {
          val items =
            transport.buckets().list(transport.projectId).execute().getItems

          Source(
            if (items == null) List.empty[Bucket]
            else
              items.asScala.map { b =>
                Bucket(
                  b.getName,
                  LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(b.getTimeCreated.getValue),
                    ZoneOffset.UTC
                  )
                )
              }.toList
          )
        })
        .flatMapMerge(1, identity)
    }
  }
}

/** Google factory. */
object GoogleStorage {

  /**
   * Returns a client for Google Cloud Storage.
   *
   * @param gt the Google transport to be used
   *
   * {{{
   * import com.zengularity.benji.google._
   *
   * def sample(implicit gt: GoogleTransport): GoogleStorage =
   *   GoogleStorage(gt)
   * }}}
   */
  def apply(gt: GoogleTransport): GoogleStorage =
    new GoogleStorage(gt, gt.requestTimeout, gt.disableGZip)
}

/*
 * Response extractor
 *
 * {{{
 * import com.zengularity.benji.google.HttpResponse
 *
 * def check(err: Throwable) = err match {
 *   case HttpResponse(code, message) => ???
 * }
 * }}}
 */
private[google] object HttpResponse {
  import com.google.api.client.googleapis.json.GoogleJsonResponseException

  def unapply(err: Throwable): Option[(Int, String)] = err match {
    case g: GoogleJsonResponseException =>
      Some(g.getStatusCode -> g.getStatusMessage)

    case _ => None
  }
}

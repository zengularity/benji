/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import scala.util.Try

import reactivemongo.api.AsyncDriver

import com.zengularity.benji.URIProvider

/**
 * GridFS transport for managing MongoDB connections.
 *
 * @param driver the ReactiveMongo async driver
 * @param db the database name
 * @param _close cleanup function
 */
final class GridFSTransport(
    val driver: AsyncDriver,
    val db: String,
    _close: () => Unit = () => ())
    extends java.io.Closeable {

  def close(): Unit = {
    _close()
  }
}

/** GridFS transport factory. */
object GridFSTransport {
  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)

  /**
   * Creates a GridFSTransport from a URI using the following format:
   * gridfs:mongodb://host:port/dbname
   */
  def apply[T](
      config: T
    )(implicit
      provider: URIProvider[T]
    ): Try[GridFSTransport] =
    provider(config).flatMap { builtUri =>
      if (builtUri == null) {
        throw new IllegalArgumentException("URI provider returned a null URI")
      }

      if (builtUri.getScheme != "gridfs") {
        throw new IllegalArgumentException(
          s"Expected URI with scheme 'gridfs'; got '${builtUri.getScheme}'"
        )
      }

      // Extract database name from path or default to "benji"
      val dbName = {
        val path = builtUri.getPath
        if (path != null && path.nonEmpty && path != "/") {
          path.replaceAll("^/+", "").replaceAll("/+$", "")
        } else {
          "benji"
        }
      }

      logger.info(s"GridFS transport configured for database: $dbName")

      Try {
        val driver = new AsyncDriver()
        new GridFSTransport(
          driver,
          dbName,
          () => {
            logger.debug("Closing GridFS driver...")
            @SuppressWarnings(
              Array("org.wartremover.warts.UnusedMethodParameter")
            )
            val _ =
              driver.close()(scala.concurrent.ExecutionContext.global)
            logger.debug("GridFS driver close initiated.")
          }
        )
      }
    }
}

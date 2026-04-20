/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import scala.util.Try

import scala.concurrent.{ ExecutionContext, Future }

import com.zengularity.benji.URIProvider
import reactivemongo.api.{ AsyncDriver, DB, MongoConnection }

/**
 * GridFS transport for managing MongoDB connections.
 *
 * @param driver the ReactiveMongo async driver
 * @param db the database name
 * @param connectionFuture Future of MongoDB connection
 * @param _close cleanup function
 */
final class GridFSTransport(
    val driver: AsyncDriver,
    val db: String,
    val connectionFuture: Future[MongoConnection],
    _close: () => Unit = () => ())
    extends java.io.Closeable {

  def close(): Unit = _close()

  def getDatabase(
      implicit
      ec: ExecutionContext
    ): Future[DB] =
    for {
      conn <- connectionFuture
      database <- conn.database(db)
    } yield database
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
      val dbName: String = {
        val path = builtUri.getPath

        if (path != null && path.nonEmpty && path != "/") {
          path.replaceAll("^/+", "").replaceAll("/+$", "")
        } else {
          "benji"
        }
      }

      logger.info(s"GridFS transport configured for database: $dbName")

      // Rebuild the URI without the scheme prefix for MongoDB connection
      val mongoUri: String = {
        val scheme = builtUri.getScheme.replace("gridfs", "mongodb")
        val authority = builtUri.getAuthority
        val path = builtUri.getPath

        s"$scheme://$authority$path"
      }

      logger.debug(s"MongoDB connection URI: $mongoUri")

      Try {
        val driver = new AsyncDriver()

        // Note: Connection string parsing and connection establishment is done lazily
        // This minimizes blocking operations at transport creation time
        val connectionFuture = driver.connect(mongoUri)

        new GridFSTransport(
          driver,
          dbName,
          connectionFuture,
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

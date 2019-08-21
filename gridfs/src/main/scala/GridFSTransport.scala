/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */
package com.zengularity.benji.gridfs

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Try }

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.{ BSONSerializationPack, MongoConnection }

import com.zengularity.benji.exception.BenjiUnknownError

final class GridFSTransport(driver: reactivemongo.api.MongoDriver, connection: MongoConnection, mongoUri: MongoConnection.ParsedURI) {
  def close(): Unit = {
    driver.close()
  }

  def gridfs(prefix: String)(implicit ec: ExecutionContext): Future[GridFS[BSONSerializationPack.type]] = {
    mongoUri.db match {
      case Some(name) =>
        connection.database(name).map(db => GridFS[BSONSerializationPack.type](db, prefix))

      case None =>
        Future.failed(new BenjiUnknownError(s"Fails to get the db from $mongoUri"))
    }
  }
}

object GridFSTransport {
  def apply(uri: String): Try[GridFSTransport] = {
    val driver = new reactivemongo.api.MongoDriver

    val res = for {
      mongoUri <- MongoConnection.parseURI(uri)
      con <- driver.connection(mongoUri, strictUri = true)
    } yield new GridFSTransport(driver, con, mongoUri)

    res.recoverWith {
      case error => Failure[GridFSTransport](new BenjiUnknownError(s"Fails to create the connection to $uri", Some(error)))
    }
  }
}


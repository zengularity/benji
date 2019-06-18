// /*
//  * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
//  */
package com.zengularity.benji.gridfs

import reactivemongo.api.MongoConnection

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.BSONSerializationPack
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

import com.zengularity.benji.exception.BenjiUnknownError
import scala.util.Success

final class GridFSTransport(driver: reactivemongo.api.MongoDriver, connection: MongoConnection, mongoUri: MongoConnection.ParsedURI) {
  def close(): Unit = {
    driver.close()
  }

  def gridfs(prefix: String)(implicit ec: ExecutionContext): Future[GridFS[BSONSerializationPack.type]] = {
    mongoUri.db match {
      case Some(name) =>
        for {
          db <- connection.database(name)
          gridfs = GridFS[BSONSerializationPack.type](db, prefix)
        } yield gridfs

      case None =>
        Future.failed(new BenjiUnknownError(s"Couldn't get the db from $mongoUri"))
    }
  }
}

object GridFSTransport {
  def apply(uri: String)(implicit ec: ExecutionContext): Try[GridFSTransport] = {
    val driver = new reactivemongo.api.MongoDriver

    val res = for {
      mongoUri <- MongoConnection.parseURI(uri)
      con = driver.connection(mongoUri)
    } yield (mongoUri, con)
    val (mongoUri, connection) = (res.map(_._1), res.map(_._2))
    connection match {
      case Success(connection) => new Success(GridFSTransport(driver, connection, mongoUri))
      case Failure(_) => Failure[GridFSTransport](new BenjiUnknownError(s"Couldn't create the connection to $uri"))
    }
  }
}

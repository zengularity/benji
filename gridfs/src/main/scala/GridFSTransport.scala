/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */
package com.zengularity.benji.gridfs

import reactivemongo.api.MongoConnection

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.BSONSerializationPack
import scala.concurrent.{ ExecutionContext, Future }

import com.zengularity.benji.exception.BenjiUnknownError

final class GridFSTransport(driver: reactivemongo.api.MongoDriver, val gridfsdb: Future[GridFS[BSONSerializationPack.type]]) {
  def close(): Unit = {
    driver.close()
  }
}

object GridFSTransport {
  def apply(uri: String, prefix: String)(implicit ec: ExecutionContext): GridFSTransport = {
    val driver = new reactivemongo.api.MongoDriver

    val gridfsdb = for {
      mongoUri <- Future.fromTry(MongoConnection.parseURI(uri))
      con = driver.connection(mongoUri)
      gridfs <- mongoUri.db match {
        case Some(name) =>
          con.database(name).map(db =>
            GridFS[BSONSerializationPack.type](db, prefix))
        case None => Future.failed(new BenjiUnknownError(s"Couldn't get the db from $mongoUri"))
      }
    } yield gridfs

    new GridFSTransport(driver, gridfsdb)
  }
}

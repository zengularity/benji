/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */
package com.zengularity.benji.gridfs

import reactivemongo.api.MongoConnection

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.BSONSerializationPack
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Future }

final class GridFSTransport(driver: reactivemongo.api.MongoDriver, val gridfsdb: Future[GridFS[BSONSerializationPack.type]]) {
  def close(): Unit = {
    driver.close()
  }
}

object GridFSTransport {
  def apply(uri: String, prefix: String): GridFSTransport = {
    val driver = new reactivemongo.api.MongoDriver

    val gridfsdb = for {
      mongoUri <- Future.fromTry(MongoConnection.parseURI(uri))
      con = driver.connection(mongoUri)
      dn <- Future(mongoUri.db.get)
      gridfs <- con.database(dn).map(db =>
        GridFS[BSONSerializationPack.type](db, prefix))
    } yield gridfs

    new GridFSTransport(driver, gridfsdb)
  }
}

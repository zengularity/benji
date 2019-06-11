/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */
package com.zengularity.benji.gridfs

import reactivemongo.api.MongoConnection

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.{ BSONSerializationPack, MongoConnectionOptions }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future}

final class GridFSTransport() {

  
}

object VFSTransport {
  def apply(uri: String, connectionOptions: MongoConnectionOptions, prefix: String) {
    val driver = new reactivemongo.api.MongoDriver
    
    val database = for {
      mongoUri <- Future.fromTry(MongoConnection.parseURI(uri))
      con = driver.connection(mongoUri)
      dn <- Future(mongoUri.db.get)
      db <- con.database(dn).map(truc =>
        GridFS[BSONSerializationPack.type](truc, prefix)
      )
    } yield db

    database
  }
}

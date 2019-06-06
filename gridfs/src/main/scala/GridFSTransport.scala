package com.zengularity.benji.gridfs

import reactivemongo.api.MongoConnection

import reactivemongo.api.gridfs.GridFS
import reactivemongo.api.{ BSONSerializationPack, MongoConnectionOptions }

final class GridFSTransport() {

  def apply(uri: String, connectionOptions: MongoConnectionOptions) {
    val driver = new reactivemongo.api.MongoDriver
    //c'est l'idée, pour l'instant je tatonne encore sur comment récupérer la db de la connexion pour la passer à GridFS(...)
    MongoConnection.parseURI(uri).map { parsedUri =>
      val connection = driver.connection(parsedUri, options = connectionOptions)
      GridFS[BSONSerializationPack.type](conn, "fs")
    }
  }

}
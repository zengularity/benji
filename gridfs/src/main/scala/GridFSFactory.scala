/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import java.net.URI

import scala.util.Try

import com.zengularity.benji.{ ObjectStorage, URIProvider }
import com.zengularity.benji.spi.{ Injector, StorageFactory, StorageScheme }

final class GridFSFactory extends StorageFactory {

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  def apply(injector: Injector, uri: URI): ObjectStorage =
    GridFSStorage(GridFSTransport[URI](uri).get)
}

object GridFSFactory {

  /**
   * Creates a GridFS storage instance from a URI string.
   * @param uriString the URI in the format gridfs:mongodb://host[:port]/[dbname]
   * @return a Try containing the GridFS storage instance
   */
  def create(uriString: String): Try[GridFSStorage] = {
    implicit val provider = URIProvider.fromStringInstance
    GridFSTransport[String](uriString).map(GridFSStorage.apply)
  }
}

/** Storage scheme for GridFS */
final class GridFSScheme extends StorageScheme {
  val scheme = "gridfs"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[GridFSFactory]
}

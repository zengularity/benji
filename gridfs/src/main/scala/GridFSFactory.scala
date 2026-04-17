/*
 * Copyright (C) 2018-2026 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import java.net.URI

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.{ Injector, StorageFactory, StorageScheme }

final class GridFSFactory extends StorageFactory {

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  def apply(injector: Injector, uri: URI): ObjectStorage =
    GridFSStorage(GridFSTransport[URI](uri).get)
}

/** Storage scheme for GridFS */
final class GridFSScheme extends StorageScheme {
  val scheme = "gridfs"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[GridFSFactory]
}

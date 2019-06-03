/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.gridfs

import java.net.URI

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.ObjectStorage

import com.zengularity.benji.spi.{ Injector, StorageFactory, StorageScheme }

/**
 * This factory is using `javax.inject`
 * to resolve `play.api.libs.ws.ahc.StandaloneAhcWSClient`.
 */
final class GridFSFactory extends StorageFactory {
  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  def apply(injector: Injector, uri: URI): ObjectStorage = {
    @inline implicit def ws: StandaloneAhcWSClient =
      injector.instanceOf(classOf[StandaloneAhcWSClient])

    GridFS[URI](uri).get
  }
}

/** Storage scheme for GridFS */
final class GridFSScheme extends StorageScheme {
  val scheme = "gridfs"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[GridFSFactory]
}

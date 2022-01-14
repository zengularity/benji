/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.vfs

import java.net.URI

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.{ Injector, StorageFactory, StorageScheme }

final class VFSFactory extends StorageFactory {

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  def apply(injector: Injector, uri: URI): ObjectStorage =
    VFSStorage(VFSTransport[URI](uri).get)
}

/** Storage scheme for VFS */
final class VFSScheme extends StorageScheme {
  val scheme = "vfs"

  @inline
  def factoryClass: Class[_ <: StorageFactory] = classOf[VFSFactory]
}

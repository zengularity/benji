package com.zengularity.benji.vfs

import scala.util.Try

import org.apache.commons.vfs2.{ FileSystemManager, VFS }

import com.zengularity.benji.StoragePack

/**
 * @param fsManager the VFS manager
 */
final class VFSTransport(val fsManager: FileSystemManager)

/** Google transport factory. */
object VFSTransport {
  import java.io.File

  import org.apache.commons.vfs2.CacheStrategy
  import org.apache.commons.vfs2.cache.NullFilesCache
  import org.apache.commons.vfs2.impl.DefaultFileSystemManager
  import org.apache.commons.vfs2.provider.temp.TemporaryFileProvider

  /**
   * Initializes a transport based on the given FS manager.
   * @param fsManager the VFS manager
   *
   * {{{
   * import org.apache.commons.vfs2.{ FileSystemManager, VFS }
   * import com.zengularity.vfs.VFSTransport
   *
   * def fsManager: FileSystemManager = VFS.getManager()
   * implicit def vfsTransport = VFSTransport(fsManager)
   * }}}
   */
  def apply(fsManager: FileSystemManager = VFS.getManager()): VFSTransport = new VFSTransport(fsManager)

  /**
   * Initialies a transport based on a temporary FS manager.
   * If the specified directory doesn't exist, it will be created.
   *
   * @param directory the path to the directory used as temporary FS root
   *
   * {{{
   * import com.zengularity.vfs.VFSTransport
   *
   * implicit def vfsTransport = VFSTransport.temporary("/tmp/foo")
   * }}}
   */
  def temporary(directory: String): Try[VFSTransport] = Try {
    val rootDir = new File(directory)

    rootDir.mkdir()

    val mngr = new DefaultFileSystemManager()

    mngr.setDefaultProvider(new TemporaryFileProvider(rootDir))

    mngr.setCacheStrategy(CacheStrategy.ON_CALL)
    mngr.setFilesCache(new NullFilesCache())
    mngr.setBaseFile(mngr.resolveFile("tmp://"))

    mngr
  }.map(apply(_))
}

object VFSStoragePack extends StoragePack {
  type Transport = VFSTransport
  type Writer[T] = play.api.libs.ws.BodyWritable[T]
}

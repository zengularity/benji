package com.zengularity.benji.vfs

import java.net.URI

import scala.util.Try

import org.apache.commons.vfs2.{ FileSystemManager, FileType, FileTypeSelector, VFS }
import org.apache.commons.vfs2.impl.StandardFileSystemManager

import com.zengularity.benji.URIProvider

/**
 * @param fsManager the VFS manager
 */
final class VFSTransport(val fsManager: FileSystemManager, _close: () => Unit = () => ()) extends java.io.Closeable {
  def close() {
    _close()
  }
}

/** VFS transport factory. */
object VFSTransport {
  import java.io.File

  import org.apache.commons.vfs2.CacheStrategy
  import org.apache.commons.vfs2.cache.NullFilesCache

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
   * Tries to create a VFSTransport from an URI using the following format:
   * vfs:scheme://path
   * Where scheme is any scheme in the providers.xml file
   *
   * {{{
   *   VFSTransport("vfs:file:///home/someuser/somedir")
   *   // or
   *   VFSTransport(new java.net.URI("vfs:file:///home/someuser/somedir"))
   * }}}
   *
   * @param config the config element used by the provider to generate the URI
   * @param provider a typeclass that try to generate an URI from the config element
   * @tparam T the config type to be consumed by the provider typeclass
   * @return Success if the VFSTransport was properly created, otherwise Failure
   */
  def apply[T](config: T)(implicit provider: URIProvider[T]): Try[VFSTransport] =
    provider(config).map { builtUri =>
      if (builtUri == null) {
        throw new IllegalArgumentException("URI provider returned a null URI")
      }

      // URI object fails to parse properly with scheme like "vfs:http"
      // So we check for "vfs" scheme and then recreate an URI without it
      if (builtUri.getScheme != "vfs") {
        throw new IllegalArgumentException("Expected URI with scheme containing \"vfs:\"")
      }

      val uri = new URI(builtUri.getSchemeSpecificPart)

      val mngr = new StandardFileSystemManager
      mngr.init()

      val scheme = uri.getScheme

      if (!mngr.hasProvider(scheme)) {
        throw new IllegalArgumentException(s"Unsupported VFS scheme : $scheme")
      }

      mngr.setBaseFile(mngr.resolveFile(uri))

      VFSTransport(mngr)
    }

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

    val mngr = new StandardFileSystemManager()

    mngr.setCacheStrategy(CacheStrategy.ON_CALL)
    mngr.setFilesCache(new NullFilesCache())
    mngr.init()

    mngr.setBaseFile(rootDir)

    val cleanup: () => Unit = { () =>
      mngr.getBaseFile.deleteAll()
      mngr.getBaseFile.delete(new FileTypeSelector(FileType.FOLDER))
      rootDir.delete()
      ()
    }

    new VFSTransport(mngr, cleanup)
  }
}

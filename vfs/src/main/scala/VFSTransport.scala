/*
 * Copyright (C) 2018-2023 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji.vfs

import java.net.URI
import java.nio.file.Files

import scala.util.Try

import org.apache.commons.vfs2.{
  FileSystemManager,
  FileType,
  FileTypeSelector,
  VFS
}
import org.apache.commons.vfs2.impl.StandardFileSystemManager
import org.apache.commons.vfs2.provider.temp.TemporaryFileProvider

import com.zengularity.benji.URIProvider

/**
 * @param fsManager the VFS manager
 */
final class VFSTransport(
    val fsManager: FileSystemManager,
    _close: () => Unit = () => ())
    extends java.io.Closeable {

  def close(): Unit = {
    _close()
  }
}

/** VFS transport factory. */
object VFSTransport {
  private val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)
  private val TemporaryScheme = "temporary"

  import org.apache.commons.vfs2.CacheStrategy
  import org.apache.commons.vfs2.cache.NullFilesCache

  /**
   * Initializes a transport based on the given FS manager.
   *
   * @param fsManager the VFS manager
   *
   * {{{
   * import org.apache.commons.vfs2.{ FileSystemManager, VFS }
   * import com.zengularity.benji.vfs.VFSTransport
   *
   * def fsManager: FileSystemManager = VFS.getManager()
   * implicit def vfsTransport = VFSTransport(fsManager)
   * }}}
   */
  def apply(fsManager: FileSystemManager = VFS.getManager()): VFSTransport =
    new VFSTransport(fsManager)

  /**
   * Tries to create a VFSTransport from an URI using the following format:
   * vfs:scheme://path
   * Where scheme is any scheme in the providers.xml file
   *
   * {{{
   * import com.zengularity.benji.vfs.VFSTransport
   *
   * def init1 =
   *   VFSTransport("vfs:file:///home/someuser/somedir")
   *
   * // or
   * def init2 =
   *   VFSTransport(new java.net.URI("vfs:file:///home/someuser/somedir"))
   * }}}
   *
   * @param config the config element used by the provider to generate the URI
   * @param provider a typeclass that try to generate an URI from the config element
   * @tparam T the config type to be consumed by the provider typeclass
   * @return Success if the VFSTransport was properly created, otherwise Failure
   */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def apply[T](
      config: T
    )(implicit
      provider: URIProvider[T]
    ): Try[VFSTransport] =
    provider(config).flatMap { builtUri =>
      if (builtUri == null) {
        throw new IllegalArgumentException("URI provider returned a null URI")
      }

      // URI object fails to parse properly with scheme like "vfs:http"
      // So we check for "vfs" scheme and then recreate an URI without it
      if (builtUri.getScheme != "vfs") {
        throw new IllegalArgumentException(
          "Expected URI with scheme containing \"vfs:\""
        )
      }

      val uri = new URI(builtUri.getSchemeSpecificPart)

      if (uri.getPath == TemporaryScheme) {
        temporary(s"benji-${System.currentTimeMillis().toString}")
      } else {
        val mngr = new StandardFileSystemManager
        mngr.init()

        val scheme = uri.getScheme

        if (!mngr.hasProvider(scheme)) {
          throw new IllegalArgumentException(
            s"Unsupported VFS scheme : $scheme"
          )
        }

        mngr.setBaseFile(mngr.resolveFile(uri))

        Try(new VFSTransport(mngr, () => mngr.close()))
      }
    }

  /**
   * Initialies a transport based on a temporary FS manager.
   * If the specified directory doesn't exist, it will be created.
   *
   * @param base the base name for the temporary directory
   *
   * {{{
   * import com.zengularity.benji.vfs.VFSTransport
   *
   * implicit def vfsTransport = VFSTransport.temporary("foo")
   * }}}
   */
  def temporary(base: String): Try[VFSTransport] = Try {
    val tmpDir = Files.createTempDirectory(base)
    val rootDir = tmpDir.toFile

    logger.info(s"Temporary folder is: ${tmpDir.toUri.toString}")

    val mngr = new StandardFileSystemManager()

    mngr.setCacheStrategy(CacheStrategy.ON_CALL)
    mngr.setFilesCache(new NullFilesCache())
    mngr.setDefaultProvider(new TemporaryFileProvider(rootDir))
    mngr.init()

    mngr.setBaseFile(rootDir)

    val cleanup: () => Unit = { () =>
      logger.debug("Closing resources ...")

      if (mngr.getBaseFile.exists()) {
        mngr.getBaseFile.deleteAll()
        mngr.getBaseFile.delete(new FileTypeSelector(FileType.FOLDER))
      }

      if (rootDir.exists()) {
        rootDir.delete()
      }

      logger.debug("Closing resources ...OK")
    }

    Runtime.getRuntime.addShutdownHook(new Thread {
      override def run() = cleanup()
    })

    new VFSTransport(mngr, cleanup)
  }
}

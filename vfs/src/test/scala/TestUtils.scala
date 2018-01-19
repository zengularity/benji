package tests.benji.vfs

import akka.stream.Materializer

import com.zengularity.benji.vfs.{ VFSStorage, VFSTransport }

object TestUtils {
  import com.typesafe.config.ConfigFactory

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @volatile private var inited = false
  lazy val config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system = akka.actor.ActorSystem("benji-vfs-tests")
  lazy val materializer = akka.stream.ActorMaterializer.create(system)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T = f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext))

  private val rootPath = s"/tmp/${System identityHashCode this}-${System identityHashCode logger}.${System.currentTimeMillis}"

  lazy val vfsTransport = VFSTransport.temporary(rootPath).get

  lazy val vfs = VFSStorage(vfsTransport)

  // ---

  def close(): Unit = if (inited) {
    try {
      org.apache.commons.io.FileUtils.deleteDirectory(
        new java.io.File(rootPath))

    } catch {
      case e: Throwable => logger.warn("fails to cleanup VFS", e)
    }

    system.terminate()

    ()
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

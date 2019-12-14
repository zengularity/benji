package tests.benji.vfs

import scala.util.control.NonFatal

import akka.actor.ActorSystem
import akka.stream.Materializer

import com.zengularity.benji.vfs.{ VFSStorage, VFSTransport }

object TestUtils {
  import com.typesafe.config.{ Config, ConfigFactory }

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile private var inited = false

  lazy val config: Config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system: ActorSystem = ActorSystem("benji-vfs-tests")

  @com.github.ghik.silencer.silent
  lazy val materializer: Materializer =
    akka.stream.ActorMaterializer.create(system)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T = f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext))

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  lazy val vfsTransport: VFSTransport = VFSTransport.temporary("benji").get

  lazy val vfs: VFSStorage = VFSStorage(vfsTransport)

  // ---

  def close(): Unit = if (inited) {
    try {
      vfsTransport.close()
    } catch {
      case NonFatal(cause) => logger.warn("Fails to release VFS", cause)
    }

    system.terminate()

    ()
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

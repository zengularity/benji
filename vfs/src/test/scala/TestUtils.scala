package tests

import scala.concurrent.{ ExecutionContext, Future }

import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import com.zengularity.ws.{ WS => TestWS }
import com.zengularity.vfs.{ VFSStorage, VFSTransport }

object TestUtils {
  import com.typesafe.config.ConfigFactory

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @volatile private var inited = false
  lazy val config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system = akka.actor.ActorSystem("cabinet-vfs-tests")
  lazy val materializer = akka.stream.ActorMaterializer.create(system)

  implicit lazy val WS: play.api.libs.ws.WSClient =
    TestWS.client()(materializer)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T = f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext))

  def consume(implicit m: Materializer): Sink[ByteString, Future[String]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Sink.fold[StringBuilder, ByteString](StringBuilder.newBuilder) {
      _ ++= _.utf8String
    }.mapMaterializedValue(_.map(_.result()))
  }

  private val rootPath = s"/tmp/${System identityHashCode this}-${System identityHashCode logger}.${System.currentTimeMillis}"

  implicit lazy val vfsTransport = VFSTransport.temporary(rootPath).get

  lazy val vfs = VFSStorage()

  // ---

  def close(): Unit = if (inited) {
    try {
      org.apache.commons.io.FileUtils.deleteDirectory(
        new java.io.File(rootPath))

    } catch {
      case e: Throwable => logger.warn("fails to cleanup GCS", e)
    }

    system.terminate()

    try { WS.close() } catch {
      case e: Throwable => logger.warn("fails to close WS", e)
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

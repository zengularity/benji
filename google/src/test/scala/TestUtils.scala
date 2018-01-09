package tests.benji.google

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import akka.stream.Materializer

import com.zengularity.benji.ws.{ WS => TestWS }
import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport }

object TestUtils {
  import com.typesafe.config.ConfigFactory

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @volatile private var inited = false
  lazy val config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system = akka.actor.ActorSystem("benji-google-tests")
  lazy val materializer = akka.stream.ActorMaterializer.create(system)

  implicit lazy val WS: play.api.libs.ws.ahc.StandaloneAhcWSClient =
    TestWS.client()(materializer)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T = f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext))

  implicit lazy val googleTransport: GoogleTransport = {
    val projectId = config.getString("google.storage.projectId")
    val application = s"benji-tests-${System identityHashCode this}"
    GoogleTransport(s"google:classpath://gcs-test.json?application=$application&projectId=$projectId").get
  }

  lazy val google = GoogleStorage()

  // ---

  def close(): Unit = if (inited) {
    import com.zengularity.benji.ObjectStorage

    implicit def m: Materializer = materializer
    implicit def ec: ExecutionContext = m.executionContext

    def storageCleanup(st: ObjectStorage) = st.buckets.collect[List]().flatMap(bs =>
      Future.sequence(bs.filter(_.name startsWith "benji-test-").map { b =>
        st.bucket(b.name).delete.recursive()
      })).map(_ => {})

    try {
      Await.result(storageCleanup(google), Duration("30s"))
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

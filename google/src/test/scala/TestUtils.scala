package tests

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import play.api.libs.iteratee.Iteratee

import com.zengularity.ws.{ WS => TestWS }
import com.zengularity.google.{ GoogleStorage, GoogleTransport }

object TestUtils {
  import com.typesafe.config.ConfigFactory
  import com.google.api.client.googleapis.auth.oauth2.GoogleCredential

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @volatile private var inited = false
  lazy val config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  def consume(implicit ec: ExecutionContext): Iteratee[Array[Byte], String] =
    Iteratee.consume[Array[Byte]]().map(new String(_))

  implicit lazy val WS: play.api.libs.ws.WSClient = TestWS.client()

  lazy val googleCredential: GoogleCredential = try {
    GoogleCredential.fromStream(getClass.getResourceAsStream("/gcs-test.json"))
  } catch {
    case e: Throwable =>
      logger.error("fails to load Google credential for testing", e)
      throw e
  }

  implicit lazy val googleTransport: GoogleTransport =
    GoogleTransport(
      googleCredential,
      config.getString("google.storage.projectId"),
      s"cabinet-tests-${System identityHashCode this}"
    )

  lazy val google = GoogleStorage()

  // ---

  def close(): Unit = if (inited) {
    import ExecutionContext.Implicits.global
    import com.zengularity.storage.ObjectStorage

    def storageCleanup[T <: ObjectStorage[T]](st: T)(implicit tr: st.Pack#Transport) = st.buckets.collect[List]().flatMap(bs =>
      Future.sequence(bs.filter(_.name startsWith "cabinet-test-").map { b =>
        val bucket: com.zengularity.storage.BucketRef[T] = st.bucket(b.name)

        bucket.objects.collect[List]().flatMap { os =>
          Future.sequence(os.map(o => bucket.obj(o.name).delete))
        }.flatMap { _ => bucket.delete }
      })).map(_ => {})

    try {
      Await.result(storageCleanup(google), Duration("30s"))
    } catch {
      case e: Throwable => logger.warn("fails to cleanup GCS", e)
    }

    try { WS.close() } catch {
      case e: Throwable => logger.warn("fails to close WS", e)
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

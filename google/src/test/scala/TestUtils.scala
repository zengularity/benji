package tests.benji.google

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import com.zengularity.benji.ws.{ WS => TestWS }
import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport }

object TestUtils {
  import com.typesafe.config.ConfigFactory
  import com.google.api.client.googleapis.auth.oauth2.GoogleCredential

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

  def consume(implicit m: Materializer): Sink[ByteString, Future[String]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Sink.fold[StringBuilder, ByteString](StringBuilder.newBuilder) {
      _ ++= _.utf8String
    }.mapMaterializedValue(_.map(_.result()))
  }

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
      s"benji-tests-${System identityHashCode this}")

  lazy val google = GoogleStorage()

  // ---

  def close(): Unit = if (inited) {
    import com.zengularity.benji.ObjectStorage

    implicit def m: Materializer = materializer
    implicit def ec: ExecutionContext = m.executionContext

    def storageCleanup[T <: ObjectStorage[T]](st: T)(implicit tr: st.Pack#Transport) = st.buckets.collect[List]().flatMap(bs =>
      Future.sequence(bs.filter(_.name startsWith "benji-test-").map { b =>
        val bucket: com.zengularity.benji.BucketRef[T] = st.bucket(b.name)

        bucket.objects.collect[List]().flatMap { os =>
          Future.sequence(os.map(o => bucket.obj(o.name).delete))
        }.flatMap { _ => bucket.delete }
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

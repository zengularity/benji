package com.zengularity.benji.google.tests

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import akka.actor.ActorSystem
import akka.stream.Materializer

import com.typesafe.config.{ Config, ConfigFactory }

import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport, WS }

object TestUtils {
  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile private var inited = false

  lazy val config: Config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system: ActorSystem = ActorSystem("benji-google-tests")

  @com.github.ghik.silencer.silent
  lazy val materializer: Materializer =
    akka.stream.ActorMaterializer.create(system)

  implicit lazy val ws: play.api.libs.ws.ahc.StandaloneAhcWSClient =
    WS.client()(materializer)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T = f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext))

  lazy val configUris: (String, List[String]) = {
    val projectId = config.getString("google.storage.projectId")
    val application = s"benji-tests-${System.identityHashCode(this).toString}"

    def uri(i: Int) = s"google:classpath://gcs-cred${i}.json?application=$application&projectId=$projectId"

    val headUri = uri(1)

    headUri -> ((2 to 10).flatMap { i =>
      if (getClass.getResource(s"/gcs-cred${i}.json") != null) {
        println(s"--> Google credentials #$i ...")

        List(uri(i))
      } else {
        List.empty
      }
    }).toList
  }

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  private lazy val googleTransports: (GoogleTransport, List[GoogleTransport]) =
    GoogleTransport(configUris._1).get -> configUris._2.
      map { GoogleTransport(_).get }

  lazy val google: List[GoogleStorage] = GoogleStorage(
    googleTransports._1) +: googleTransports._2.map(GoogleStorage(_))

  // ---

  def close(): Unit = if (inited) {
    implicit def m: Materializer = materializer
    implicit def ec: ExecutionContext = m.executionContext

    def storageCleanup(it: Iterator[GoogleStorage]) = {
      val st = it.next()

      st.buckets.collect[List]().flatMap(bs =>
        Future.sequence(bs.filter(_.name startsWith "benji-test-").map { b =>
          st.bucket(b.name).delete.recursive()
        })).map(_ => {})
    }

    try {
      Await.result(storageCleanup(
        Iterator.continually(google).flatten), Duration("30s"))

    } catch {
      case e: Throwable => logger.warn("fails to cleanup GCS", e)
    }

    system.terminate()

    try { ws.close() } catch {
      case e: Throwable => logger.warn("fails to close WS", e)
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

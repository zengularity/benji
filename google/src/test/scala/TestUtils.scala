package com.zengularity.benji.google.tests

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import com.typesafe.config.{ Config, ConfigFactory }

import akka.actor.ActorSystem

import akka.stream.Materializer

import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport, WS }

object TestUtils {
  val logger: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("tests")

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile private var inited = false

  lazy val config: Config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system: ActorSystem = ActorSystem("benji-google-tests")

  lazy val materializer: Materializer =
    akka.stream.ActorMaterializer.create(system)

  implicit lazy val ws: play.api.libs.ws.ahc.StandaloneAhcWSClient =
    WS.client()(materializer)

  def withMatEx[T](
      f: org.specs2.concurrent.ExecutionEnv => T
    )(implicit
      m: Materializer
    ): T = f(
    org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext)
  )

  /**
   * Initialize storage-testbench if enabled in configuration.
   * This verifies that testbench is running (it must be started separately).
   */
  private def initTestbench(): Unit = {
    try {
      val useTestbench = config.getBoolean("google.storage.testbench.enabled")
      if (useTestbench) {
        val host = config.getString("google.storage.testbench.host")
        val port = config.getInt("google.storage.testbench.port")

        logger.info(s"Checking storage-testbench on $host:$port")
        StorageTestbench
          .checkReady(host, port, 5000)
          .get // Will throw if not ready
      }
    } catch {
      case _: com.typesafe.config.ConfigException =>
        logger.debug("testbench not configured, using default endpoint")
      case e: Throwable =>
        logger.error("Failed to connect to testbench", e)
        throw e
    }
  }

  lazy val configUri: String = {
    // Initialize testbench first
    initTestbench()

    val projectId = config.getString("google.storage.projectId")
    val application = s"benji-tests-${System.identityHashCode(this).toString}"

    // If testbench is enabled, use its endpoint; otherwise use default GCS
    val baseUri = if (isTestbenchEnabled) {
      val host = config.getString("google.storage.testbench.host")
      val port = config.getInt("google.storage.testbench.port")
      s"google:classpath://gcs-test.json?application=$application&projectId=$projectId&baseRestUrl=http://$host:$port"
    } else {
      s"google:classpath://gcs-test.json?application=$application&projectId=$projectId"
    }

    baseUri
  }

  def isTestbenchEnabled: Boolean = {
    try {
      config.getBoolean("google.storage.testbench.enabled")
    } catch {
      case _: Throwable => false
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  lazy val googleTransport: GoogleTransport = {
    if (isTestbenchEnabled) {
      val host = config.getString("google.storage.testbench.host")
      val port = config.getInt("google.storage.testbench.port")
      val projectId = config.getString("google.storage.projectId")
      val application =
        s"benji-tests-${System.identityHashCode(this).toString}"
      val baseRestUrl = s"http://$host:$port"

      // Verify testbench is reachable
      initTestbench()

      // Mock credential with far-future expiration;
      // testbench accepts any Bearer token,
      // so no real Google Auth is needed.
      val fakeToken = new com.google.auth.oauth2.AccessToken(
        "testbench-fake-token",
        new java.util.Date(System.currentTimeMillis() + 86400000L)
      )

      val credential =
        com.google.auth.oauth2.GoogleCredentials.create(fakeToken)

      GoogleTransport(
        credential,
        projectId,
        application,
        baseRestUrl = baseRestUrl
      )
    } else {
      GoogleTransport(configUri).get
    }
  }

  lazy val google: GoogleStorage = GoogleStorage(googleTransport)

  // ---

  def close(): Unit = if (inited) {
    import com.zengularity.benji.ObjectStorage

    implicit def m: Materializer = materializer
    implicit def ec: ExecutionContext = m.executionContext

    def storageCleanup(st: ObjectStorage) = st.buckets
      .collect[List]()
      .flatMap(bs =>
        Future.sequence(bs.filter(_.name startsWith "benji-test-").map { b =>
          st.bucket(b.name).delete.recursive()
        })
      )
      .map(_ => {})

    try {
      Await.result(storageCleanup(google), Duration("30s"))
    } catch {
      case e: Throwable => logger.warn("fails to cleanup GCS", e)
    }

    system.terminate()

    try { ws.close() }
    catch {
      case e: Throwable => logger.warn("fails to close WS", e)
    }

    // Stop testbench if it was started
    StorageTestbench.stop()
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

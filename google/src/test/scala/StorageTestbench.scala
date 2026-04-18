package com.zengularity.benji.google.tests

import java.net.{ HttpURLConnection, URL }
import java.util.concurrent.TimeoutException

import scala.util.{ Failure, Success, Try }

/**
 * Verifies that storage-testbench is running and ready.
 * storage-testbench must be started separately (via Docker or Python).
 *
 * GitHub: https://github.com/googleapis/storage-testbench
 *
 * This object only checks connectivity to an existing testbench instance.
 * It does NOT start the process automatically.
 *
 * Usage:
 *   StorageTestbench.checkReady().foreach(_ => println("ready"))
 */
object StorageTestbench {
  private val logger = org.slf4j.LoggerFactory.getLogger("storage-testbench")

  /**
   * Check if storage-testbench is running and ready.
   *
   * @param host hostname to check (default: localhost)
   * @param port port to check (default: 9000)
   * @param timeout max milliseconds to wait (default: 5000)
   * @return Try[Unit] - Success if ready, Failure if not reachable
   */
  def checkReady(
      host: String = "localhost",
      port: Int = 9000,
      timeout: Long = 5000
    ): Try[Unit] = waitForReady(host, port, timeout)

  /**
   * Stop is no-op since we don't manage the process lifecycle.
   */
  def stop(): Unit = {
    // No-op: testbench is managed externally (Docker, Python, etc.)
    logger.debug("testbench lifecycle managed externally")
  }

  /**
   * Wait for testbench to be ready by checking health endpoint.
   */
  private def waitForReady(
      host: String,
      port: Int,
      timeout: Long
    ): Try[Unit] = {
    val deadline = System.currentTimeMillis() + timeout
    val url = s"http://$host:$port"

    var lastError: Option[Throwable] = None

    while (System.currentTimeMillis() < deadline) {
      try {
        val conn =
          new URL(url).openConnection().asInstanceOf[HttpURLConnection]

        conn.setConnectTimeout(500)
        conn.setReadTimeout(500)

        val responseCode = conn.getResponseCode

        conn.disconnect()

        if (responseCode == 200) {
          logger.info(s"storage-testbench is ready at $url")

          return Success(())
        }
      } catch {
        case e: Throwable =>
          lastError = Some(e)
      }

      Thread.sleep(100)
    }

    val timeoutError = new TimeoutException(
      s"storage-testbench not ready at $url after ${timeout}ms. " +
        s"Make sure testbench is running: docker-compose -f google/docker-compose.testbench.yml up -d"
    )

    lastError.foreach(timeoutError.addSuppressed)
    logger.error(timeoutError.getMessage)
    Failure(timeoutError)
  }
}

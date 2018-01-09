package tests.benji.s3

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

import akka.stream.Materializer
import akka.stream.contrib.TestKit

import com.zengularity.benji.ws.{ WS => TestWS }
import com.zengularity.benji.s3.S3

object TestUtils {
  import com.typesafe.config.ConfigFactory

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @volatile private var inited = false
  lazy val config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system = akka.actor.ActorSystem("benji-s3-tests")
  lazy val materializer = akka.stream.ActorMaterializer.create(system)

  implicit lazy val WS: play.api.libs.ws.ahc.StandaloneAhcWSClient =
    TestWS.client()(materializer)

  lazy val (awsAccessKey, awsSecretKey, awsHost, awsProtocol) = (
    config.getString("aws.s3.accessKey"),
    config.getString("aws.s3.secretKey"),
    config.getString("aws.s3.host"),
    config.getString("aws.s3.protocol"))

  lazy val (cephAccessKey, cephSecretKey, cephHost, cephProtocol) = (
    config.getString("ceph.s3.accessKey"),
    config.getString("ceph.s3.secretKey"),
    config.getString("ceph.s3.host"),
    config.getString("ceph.s3.protocol"))

  lazy val aws = S3(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  lazy val awsVirtualHost =
    S3.virtualHost(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  lazy val awsFromVirtualHostStyleURL = S3(s"s3:$awsProtocol://$awsAccessKey:$awsSecretKey@$awsHost/?style=virtualHost").get
  lazy val awsFromPathStyleURL = S3(s"s3:$awsProtocol://$awsAccessKey:$awsSecretKey@$awsHost/?style=path").get

  lazy val ceph = S3(cephAccessKey, cephSecretKey, cephProtocol, cephHost)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T =
    TestKit.assertAllStagesStopped(f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext)))

  // ---

  def close(): Unit = if (inited) {
    implicit def m: Materializer = materializer
    implicit def ec: ExecutionContext = m.executionContext

    import com.zengularity.benji.ObjectStorage

    def storageCleanup(st: ObjectStorage) = st.buckets.collect[List]().flatMap(bs =>
      Future.sequence(bs.filter(_.name startsWith "benji-test-").map { b =>
        st.bucket(b.name).delete.recursive()
      })).map(_ => {})

    try {
      Await.result(storageCleanup(aws), Duration("30s"))
    } catch {
      case e: Throwable => logger.warn("fails to cleanup AWS", e)
    }

    try {
      Await.result(storageCleanup(ceph), Duration("30s"))
    } catch {
      case e: Throwable => logger.warn("fails to cleanup Ceph", e)
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

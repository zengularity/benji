package tests

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import play.api.libs.iteratee.Iteratee

import com.zengularity.ws.{ WS => TestWS }
import com.zengularity.s3.S3

object TestUtils {
  import com.typesafe.config.ConfigFactory

  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @volatile private var inited = false
  lazy val config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val (awsAccessKey, awsSecretKey, awsHost, awsProtocol) = (
    config.getString("aws.s3.accessKey"),
    config.getString("aws.s3.secretKey"),
    config.getString("aws.s3.host"),
    config.getString("aws.s3.protocol")
  )

  lazy val (cephAccessKey, cephSecretKey, cephHost, cephProtocol) = (
    config.getString("ceph.s3.accessKey"),
    config.getString("ceph.s3.secretKey"),
    config.getString("ceph.s3.host"),
    config.getString("ceph.s3.protocol")
  )

  lazy val aws = S3(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  lazy val awsVirtualHost =
    S3.virtualHost(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  lazy val ceph = S3(cephAccessKey, cephSecretKey, cephProtocol, cephHost)

  def consume(implicit ec: ExecutionContext): Iteratee[Array[Byte], String] =
    Iteratee.consume[Array[Byte]]().map(new String(_))

  implicit lazy val WS: play.api.libs.ws.WSClient = TestWS.client()

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
      Await.result(storageCleanup(aws), Duration("30s"))
    } catch {
      case e: Throwable => logger.warn("fails to cleanup AWS", e)
    }

    try {
      Await.result(storageCleanup(ceph), Duration("30s"))
    } catch {
      case e: Throwable => logger.warn("fails to cleanup Ceph", e)
    }

    try { WS.close() } catch {
      case e: Throwable => logger.warn("fails to close WS", e)
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run() = close()
  })
}

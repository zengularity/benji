package tests

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration.Duration

import play.api.libs.iteratee.Iteratee

import com.zengularity.s3.{ S3, WS => S3WS }

object TestUtils {
  import com.typesafe.config.ConfigFactory

  lazy val config = ConfigFactory.load("tests.conf")

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

  def aws(implicit ec: ExecutionContext) =
    S3(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  def awsVirtualHost(implicit ec: ExecutionContext) =
    S3.virtualHost(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  def ceph(implicit ec: ExecutionContext) =
    S3(cephAccessKey, cephSecretKey, cephProtocol, cephHost)

  def consume(implicit ec: ExecutionContext): Iteratee[Array[Byte], String] =
    Iteratee.consume[Array[Byte]]().map(new String(_))

  implicit lazy val WS: play.api.libs.ws.WSClient = S3WS.client()

  def close(): Unit = {
    import ExecutionContext.Implicits.global

    def awsCleanup = aws.buckets.flatMap(bs =>
      Future.sequence(bs.filter(_.name startsWith "test-").map { b =>
        val bucket = aws.bucket(b.name)
        bucket.objects.flatMap { os =>
          Future.sequence(os.map(o => bucket.obj(o.key).delete))
        }.flatMap { _ => bucket.delete }
      })).map(_ => {})

    def cephCleanup = ceph.buckets.flatMap(bs =>
      Future.sequence(bs.filter(_.name startsWith "test-").map { b =>
        val bucket = ceph.bucket(b.name)
        bucket.objects.flatMap { os =>
          Future.sequence(os.map(o => bucket.obj(o.key).delete))
        }.flatMap { _ => bucket.delete }
      })).map(_ => {})

    def cleanup = for {
      _ <- awsCleanup
      _ <- cephCleanup
    } yield ()

    try {
      Await.result(cleanup, Duration("30s"))
    } catch {
      case e: Throwable => e.printStackTrace()
    }

    try { WS.close() } catch { case e: Throwable => e.printStackTrace() }
  }
}

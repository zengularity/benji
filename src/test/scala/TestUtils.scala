package tests

import scala.concurrent.ExecutionContext

import play.api.libs.iteratee.Iteratee

import com.zengularity.s3.{ S3, WS => S3WS }

object TestUtils {
  import com.typesafe.config.ConfigFactory

  lazy val config = ConfigFactory.load("tests.conf")

  lazy val (awsAccessKey, awsSecretKey, awsHost, awsProtocol) = {
    (
      config.getString("aws.s3.accessKey"),
      config.getString("aws.s3.secretKey"),
      config.getString("aws.s3.host"),
      config.getString("aws.s3.protocol")
    )
  }

  lazy val (cephAccessKey, cephSecretKey, cephHost, cephProtocol) = {
    (
      config.getString("ceph.s3.accessKey"),
      config.getString("ceph.s3.secretKey"),
      config.getString("ceph.s3.host"),
      config.getString("ceph.s3.protocol")
    )
  }

  def aws(implicit ec: ExecutionContext) =
    S3(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  def ceph(implicit ec: ExecutionContext) =
    S3(cephAccessKey, cephSecretKey, cephProtocol, cephHost)

  def consume(implicit ec: ExecutionContext): Iteratee[Array[Byte], String] =
    Iteratee.consume[Array[Byte]]().map(new String(_))

  implicit lazy val WS: play.api.libs.ws.WSClient = S3WS.client()

  def close(): Unit = {
    try { WS.close() } catch { case e: Throwable => e.printStackTrace() }
  }
}

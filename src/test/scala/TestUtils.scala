package tests

import scala.concurrent.ExecutionContext

import play.api.Application
import play.api.libs.iteratee.Iteratee

import com.zengularity.s3.S3

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

  def aws(implicit ec: ExecutionContext, app: Application) =
    S3(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  def ceph(implicit ec: ExecutionContext, app: Application) =
    S3(cephAccessKey, cephSecretKey, cephProtocol, cephHost)

  def consume(implicit ec: ExecutionContext): Iteratee[Array[Byte], String] =
    Iteratee.consume[Array[Byte]]().map(new String(_))

}

package com.zengularity.benji.s3.tests

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }

import com.typesafe.config.{ Config, ConfigFactory }

import akka.actor.ActorSystem
import akka.stream.Materializer

import com.zengularity.benji.s3.{ S3, WSS3 }

object TestUtils {
  val logger = org.slf4j.LoggerFactory.getLogger("tests")

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile private var inited = false

  lazy val config: Config = {
    inited = true
    ConfigFactory.load("tests.conf")
  }

  lazy val system: ActorSystem = ActorSystem("benji-s3-tests")

  lazy val materializer: Materializer =
    akka.stream.ActorMaterializer.create(system)

  implicit lazy val WS: play.api.libs.ws.ahc.StandaloneAhcWSClient =
    S3.client()(materializer)

  private lazy val (awsAccessKey, awsSecretKey, awsHost, awsProtocol) = (
    config.getString("aws.s3.accessKey"),
    config.getString("aws.s3.secretKey"),
    config.getString("aws.s3.host"),
    config.getString("aws.s3.protocol"))

  lazy val awsRegion: String = config.getString("aws.s3.region")

  private lazy val (cephAccessKey, cephSecretKey, cephHost, cephProtocol) = (
    config.getString("ceph.s3.accessKey"),
    config.getString("ceph.s3.secretKey"),
    config.getString("ceph.s3.host"),
    config.getString("ceph.s3.protocol"))

  lazy val aws: WSS3 = S3(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  // TODO: Remove once V2 is no longer supported by AWS
  lazy val awsVirtualHost: WSS3 =
    S3.virtualHost(awsAccessKey, awsSecretKey, awsProtocol, awsHost)

  lazy val awsVirtualHostV4: WSS3 = S3.virtualHostAwsV4(
    awsAccessKey, awsSecretKey, awsProtocol, awsHost, awsRegion)

  val virtualHostStyleUrl: String = s"s3:$awsProtocol://$awsAccessKey:$awsSecretKey@$awsHost/?style=virtualHost"

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  lazy val awsFromVirtualHostStyleURL: WSS3 = S3(virtualHostStyleUrl).get

  val virtualHostStyleUrlV4: String = s"s3:$awsProtocol://$awsAccessKey:$awsSecretKey@$awsHost/?style=virtualHost&awsRegion=$awsRegion"

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  lazy val awsFromVirtualHostStyleURLV4: WSS3 = S3(virtualHostStyleUrlV4).get

  @SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
  lazy val awsFromPathStyleURL: WSS3 =
    S3(s"s3:$awsProtocol://$awsAccessKey:$awsSecretKey@$awsHost/?style=path").get

  lazy val ceph: WSS3 = S3(cephAccessKey, cephSecretKey, cephProtocol, cephHost)

  def withMatEx[T](f: org.specs2.concurrent.ExecutionEnv => T)(implicit m: Materializer): T = f(org.specs2.concurrent.ExecutionEnv.fromExecutionContext(m.executionContext))

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

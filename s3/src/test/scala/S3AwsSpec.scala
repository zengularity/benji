package tests.benji.s3

import scala.concurrent.duration._

import akka.stream.Materializer

import play.api.libs.ws.DefaultBodyWritables._

import org.specs2.concurrent.{ ExecutionEnv => EE }
import org.specs2.mutable.Specification

import tests.benji.StorageCommonSpec

class S3AwsSpec extends Specification with AwsTests {
  "S3 Amazon" title

  sequential

  awsSuite(
    "in path style",
    TestUtils.aws)(TestUtils.materializer)

  awsSuite(
    "in virtual host style",
    TestUtils.awsVirtualHost)(TestUtils.materializer)

  awsMinimalSuite(
    "in path style with URI",
    TestUtils.awsFromPathStyleURL)(TestUtils.materializer)

  awsMinimalSuite(
    "in virtual host with URI",
    TestUtils.awsFromVirtualHostStyleURL)(TestUtils.materializer)
}

sealed trait AwsTests extends StorageCommonSpec { specs: Specification =>
  import TestUtils.withMatEx

  def awsMinimalSuite(
    label: String,
    s3f: => com.zengularity.benji.s3.WSS3)(implicit m: Materializer) = s"S3 client $label" should {
    val bucketName = s"benji-test-${System identityHashCode s3f}"

    withMatEx { implicit ee: EE => minimalCommonTests(s3f, bucketName) }
  }

  def awsSuite(
    label: String,
    s3f: => com.zengularity.benji.s3.WSS3)(implicit m: Materializer) = s"S3 client $label" should {
    val bucketName = s"benji-test-${System identityHashCode s3f}"

    withMatEx { implicit ee: EE => commonTests(s3f, bucketName) }

    "Metadata of a file" in withMatEx { implicit ee: EE =>
      s3f.bucket(bucketName).obj("testfile.txt").
        headers() must beLike[Map[String, Seq[String]]] {
          case headers => headers.get("x-amz-meta-foo").
            aka("custom metadata") must beSome(Seq("bar"))
        }.await(1, 5.seconds)
    }

    "Use correct toString format on bucket" in {
      s3f.bucket("bucketName").toString must_== "WSS3BucketRef(bucketName)"
    }

    "Use correct toString format on object" in {
      s3f.bucket("bucketName").obj("objectName").toString must_== "WSS3ObjectRef(bucketName, objectName)"
    }
  }
}

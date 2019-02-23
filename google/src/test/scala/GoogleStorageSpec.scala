package tests.benji.google

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.stream.contrib.TestKit.assertAllStagesStopped

import play.api.libs.ws.DefaultBodyWritables._

import org.specs2.concurrent.ExecutionEnv

import com.zengularity.benji.google.GoogleObjectRef

import tests.benji.{ StorageCommonSpec, VersioningCommonSpec }

import com.zengularity.benji.google.tests.TestUtils

class GoogleStorageSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification with StorageCommonSpec with VersioningCommonSpec {

  import TestUtils.google
  import tests.benji.StreamUtils._

  "Google Cloud Storage" title

  sequential

  implicit def materializer: Materializer = TestUtils.materializer

  "Client" should {
    val bucketName = s"benji-test-${System identityHashCode this}"
    val objName = "testfile.txt"

    commonTests("google", google, bucketName)
    commonVersioningTests(google, sampleVersionId = "7")

    val fileStart = "hello world !!!"

    val partCount = 3
    s"Write file in $bucketName bucket using $partCount parts" in {
      val filetest = google.bucket(bucketName).obj(objName)

      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var b = 0.toByte
      def nextByte = {
        b = (b + 1).toByte
        b
      }

      def body = fileStart.getBytes("UTF-8") ++ Array.fill(
        GoogleObjectRef.defaultThreshold.bytes.toInt - 3)(
          nextByte) ++ "XXX".getBytes("UTF-8")

      type PutReq = filetest.RESTPutRequest[Array[Byte], Unit]

      filetest.put[Array[Byte], Unit] must beLike[PutReq] {
        case req =>
          val upload = req({}, metadata = Map("foo" -> "bar"))(
            (_, _) => Future.successful({}))

          (repeat(partCount - 1)(body).++(Source.single(
            body.drop(10) ++ "ZZZ".getBytes("UTF-8"))) runWith upload).
            flatMap { _ => filetest.exists } must beTrue.
            await(1, 10.seconds)
      }
    }

    s"Metadata of a file $objName" in assertAllStagesStopped {
      val objRef = google.bucket(bucketName).obj(objName)

      objRef.headers() must beLike[Map[String, Seq[String]]] {
        case headers => headers.get("metadata.foo") must beSome(Seq("bar"))
      }.await(1, 5.seconds)
    }

    "Write and copy files (w/o GZip compression)" in assertAllStagesStopped {
      val woGzip = google.withDisabledGZip(true)
      val file1 = woGzip.bucket(bucketName).obj("testfile1.txt")
      val file2 = woGzip.bucket(bucketName).obj("testfile2.txt")

      file1.exists must beFalse.await(1, 10.seconds) and {
        val put = file1.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith put }.flatMap(_ => file1.exists).
          aka("file1 exists") must beTrue.await(1, 10.seconds)
      } and {
        file1.copyTo(file2).flatMap(_ => file2.exists) must beTrue.
          await(1, 10.seconds)
      } and {
        (for {
          _ <- Future.sequence(Seq(file1.delete(), file2.delete()))
          a <- file1.exists
          b <- file2.exists
        } yield a -> b) must beEqualTo(false -> false).await(1, 10.seconds)
      }
    }

    "Use correct toString format on bucket" in {
      google.bucket("bucketName").toString must_== "GoogleBucketRef(bucketName)"
    }

    "Use correct toString format on object" in {
      google.bucket("bucketName").obj("objectName").toString must_== "GoogleObjectRef(bucketName, objectName)"
    }
  }
}

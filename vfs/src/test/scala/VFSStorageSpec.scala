package tests.benji.vfs

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.stream.Materializer
import akka.stream.contrib.TestKit.assertAllStagesStopped
import akka.stream.scaladsl.Source

import play.api.libs.ws.DefaultBodyWritables._

import org.specs2.concurrent.ExecutionEnv

import com.zengularity.benji.vfs.VFSObjectRef

import tests.benji.StorageCommonSpec

class VFSStorageSpec(implicit ee: ExecutionEnv) extends org.specs2.mutable.Specification with StorageCommonSpec {

  import tests.benji.StreamUtils._
  import TestUtils.vfs

  "VFS Cloud Storage" title

  sequential

  implicit def materializer: Materializer = TestUtils.materializer

  "VFS client" should {
    val bucketName = s"benji-test-${System identityHashCode this}"

    commonTests(vfs, bucketName)

    val fileStart = "hello world !!!"

    val partCount = 3
    s"Write file in $bucketName bucket using $partCount parts" in assertAllStagesStopped {
      val filetest = vfs.bucket(bucketName).obj("testfile.txt")
      var b = 0.toByte
      def nextByte = {
        b = (b + 1).toByte
        b
      }

      def body = fileStart.getBytes("UTF-8") ++ Array.fill(
        VFSObjectRef.defaultThreshold.bytes.toInt - 3)(nextByte) ++ "XXX".getBytes("UTF-8")

      filetest.put[Array[Byte], Unit].
        aka("request") must beLike[filetest.RESTPutRequest[Array[Byte], Unit]] {
          case req =>
            val upload = req({})((_, _) => Future.successful({}))

            (repeat(partCount - 1)(body).++(Source.single(
              body.drop(10) ++ "ZZZ".getBytes("UTF-8"))) runWith upload).
              flatMap { _ => filetest.exists } must beTrue.
              await(1, 10.seconds)
        }
    }

    "Use correct toString format on bucket" in {
      vfs.bucket("bucketName").toString must_== "VFSBucketRef(bucketName)"
    }

    "Use correct toString format on object" in {
      vfs.bucket("bucketName").obj("objectName").toString must_== "VFSObjectRef(bucketName, objectName)"
    }
  }
}

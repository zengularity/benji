package tests.benji.vfs

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.DefaultBodyWritables._

import com.zengularity.benji.vfs.VFSObjectRef
import tests.benji.StorageCommonSpec

import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.AfterAll

final class VFSStorageSpec(implicit ee: ExecutionEnv)
    extends org.specs2.mutable.Specification
    with StorageCommonSpec
    with AfterAll {

  import tests.benji.StreamUtils._
  import TestUtils.vfs

  "VFS Cloud Storage".title

  sequential

  implicit def materializer: Materializer = TestUtils.materializer

  "VFS client" should {
    val bucketName = s"benji-test-${random.nextInt().toString}"

    commonTests("vfs", vfs, bucketName)

    val fileStart = "hello world !!!"

    val partCount = 3
    s"Write file in $bucketName bucket using ${partCount.toString} parts" in assertAllStagesStopped {
      val filetest = vfs.bucket(bucketName).obj("testfile.txt")

      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      var b = 0.toByte
      def nextByte = {
        b = (b + 1).toByte
        b
      }

      def body = fileStart.getBytes("UTF-8") ++ Array.fill(
        VFSObjectRef.defaultThreshold.bytes.toInt - 3
      )(nextByte) ++ "XXX".getBytes("UTF-8")

      filetest
        .put[Array[Byte], Unit]
        .aka("request") must beLike[filetest.PutRequest[Array[Byte], Unit]] {
        case req =>
          val upload = req({})((_, _) => Future.successful({}))

          (repeat(partCount - 1)(body).++(
            Source.single(body.drop(10) ++ "ZZZ".getBytes("UTF-8"))
          ) runWith upload).flatMap { _ => filetest.exists } must beTrue.await(
            1,
            10.seconds
          )
      }
    }

    "Use correct toString format on bucket" in {
      vfs.bucket("bucketName").toString must_== "VFSBucketRef(bucketName)"
    }

    "Use correct toString format on object" in {
      vfs
        .bucket("bucketName")
        .obj("objectName")
        .toString must_== "VFSObjectRef(bucketName, objectName)"
    }
  }

  override def afterAll(): Unit = {
    TestUtils.vfsTransport.close()
  }
}

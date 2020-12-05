package tests.benji.s3

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.scaladsl.Source

import play.api.libs.ws.DefaultBodyWritables._

import com.zengularity.benji.s3.tests.TestUtils
import tests.benji.{ StorageCommonSpec, VersioningCommonSpec }

import org.specs2.concurrent.{ ExecutionEnv => EE }

final class S3CephSpec extends org.specs2.mutable.Specification
  with StorageCommonSpec with VersioningCommonSpec with S3Spec {

  import tests.benji.StreamUtils._
  import TestUtils.{ ceph, withMatEx }

  "S3 Ceph" title

  sequential

  @inline private implicit def materializer = TestUtils.materializer

  "Client" should {
    val bucketName = s"benji-test-${random.nextInt().toString}"
    val objName = "testfile.txt"

    withMatEx { implicit ee: EE =>
      commonTests("ceph", ceph, bucketName)
      commonVersioningTests(ceph, sampleVersionId = "t1Uelqn.uwzanWblaNOrIWpgWapViNXY")
    }

    s3Suite(ceph, bucketName, objName)

    s"Write file in $bucketName bucket using multipart" >> {
      "with the default maximum" in withMatEx { implicit ee: EE =>
        val filetest = ceph.bucket(bucketName).obj(objName)
        def part(b: Byte) = Array.fill[Byte](
          filetest.defaultThreshold.bytes.toInt)(b)

        def body = Source.single(
          "hello world !!!".getBytes("UTF-8")) ++ Source.fromIterator(() =>
            Seq(part(1), part(2), part(3)).iterator)

        type PutReq = filetest.RESTPutRequest[Array[Byte], Unit]

        filetest.put[Array[Byte], Unit].aka("request") must beLike[PutReq] {
          case req =>
            val upload = req({}, metadata = Map("foo" -> "bar"))(
              (_, _) => Future.successful({}))

            (body runWith upload).flatMap(_ => filetest.exists).
              aka("upload") must beTrue.await(2, /*10*/ 45.seconds)
        }
      }

      "with a maximum of 2 parts instead of 3" in withMatEx { implicit ee: EE =>
        val obj = ceph.bucket(bucketName).obj("testfile3.txt")

        @SuppressWarnings(Array("org.wartremover.warts.Var"))
        @volatile var partCount = 0

        // part = max part (max size up to the default threshold)
        // body = 3 max parts
        val part = Array.fill[Byte](obj.defaultThreshold.bytes.toInt)(1)
        def body = repeat(3)(part)

        def req = obj.put[Array[Byte], Unit].withMaxPart(2)
        val upload = req({}, size = Some(part.size * 3L)) {
          (_, _) => Future.successful { partCount = partCount + 1 }
        }

        (body runWith upload).flatMap(_ => obj.exists).
          aka("upload") must beTrue.await(2, /*10*/ 45.seconds) and {
            partCount must_== 2
          }
      }
    }
  }
}

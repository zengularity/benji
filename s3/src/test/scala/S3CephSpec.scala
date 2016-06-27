package tests

import scala.concurrent.Future
import scala.concurrent.duration._

import scala.collection.immutable.Seq

import akka.stream.scaladsl.{ Sink, Source }

import org.specs2.concurrent.{ ExecutionEnv => EE }

import com.zengularity.storage.{ Bucket, ByteRange, Object }

import Sources.repeat
import TestUtils.{ WS, ceph, consume, withMatEx }

class S3CephSpec extends org.specs2.mutable.Specification {
  "S3 Ceph" title

  sequential

  @inline implicit def materializer = TestUtils.materializer

  "Ceph client" should {
    val bucketName = s"cabinet-test-${System identityHashCode this}"

    "Access the system" in withMatEx { implicit ee: EE =>
      val bucket = ceph.bucket(bucketName)

      bucket.create().flatMap(_ => ceph.buckets.collect[List]()).map(
        _.exists(_.name == bucketName)
      ) must beTrue.await(1, 10.seconds) and (
          bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds)
        )
    }

    "Create buckets and files" in withMatEx { implicit ee: EE =>
      val s3 = ceph
      val name = s"cabinet-test-${System identityHashCode s3}"

      val objects = for {
        _ <- s3.bucket(name).create()
        _ <- Source.single("Hello".getBytes) runWith s3.
          bucket(name).obj("testfile.txt").put[Array[Byte]]
        _ <- (s3.buckets() runWith Sink.seq[Bucket])
        o <- (s3.bucket(name).objects() runWith Sink.seq[Object])
      } yield o

      objects must beLike[Seq[Object]] {
        case list => list.find(_.name == "testfile.txt") must beSome
      }.await(1, 10.seconds)
    }

    "List of all buckets" in withMatEx { implicit ee: EE =>
      ceph.buckets.collect[List]().
        map(_.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
    }

    s"Write file in $bucketName bucket using multipart" >> {
      "with the default maximum" in withMatEx { implicit ee: EE =>
        val filetest = ceph.bucket(bucketName).obj("testfile.txt")
        val part = Array.fill[Byte](filetest.defaultThreshold.bytes.toInt)(1)
        def body = Source.single(
          "hello world !!!".getBytes("UTF-8")
        ) ++ repeat(3)(part)
        type PutReq = filetest.RESTPutRequest[Array[Byte], Unit]

        filetest.put[Array[Byte], Unit].aka("request") must beLike[PutReq] {
          case req =>
            val upload = req({})((_, _) => Future.successful({}))

            (body runWith upload).flatMap(_ => filetest.exists).
              aka("upload") must beTrue.await(2, 10.seconds)
        }
      }

      "with a maximum of 2 parts instead of 3" in withMatEx { implicit ee: EE =>
        val obj = ceph.bucket(bucketName).obj("testfile3.txt")
        @volatile var partCount = 0

        // part = max part (max size up to the default threshold)
        // body = 3 max parts
        val part = Array.fill[Byte](obj.defaultThreshold.bytes.toInt)(1)
        def body = repeat(3)(part)

        def req = obj.put[Array[Byte], Unit].withMaxPart(2)
        val upload = req({}, size = Some(part.size * 3)) {
          (_, _) => Future.successful { partCount = partCount + 1 }
        }

        (body runWith upload).flatMap(_ => obj.exists).
          aka("upload") must beTrue.await(2, 10.seconds) and {
            partCount must_== 2
          }
      }
    }

    s"Get contents of $bucketName bucket" in withMatEx { implicit ee: EE =>
      ceph.bucket(bucketName).objects.collect[List]().map(
        _.exists(_.name == "testfile.txt")
      ) must beTrue.await(1, 10.seconds)
    }

    "Create & delete buckets" in withMatEx { implicit ee: EE =>
      val s3 = ceph
      val name = s"test2-${System identityHashCode s3}"
      val bucket = s3.bucket(name)

      bucket.exists must beFalse.await(1, 10.seconds) and {
        val bucketsWithTestRemovable =
          bucket.create().flatMap(_ => s3.buckets.collect[List]())

        bucketsWithTestRemovable.map(
          _.exists(_.name == name)
        ) must beTrue.await(1, 10.seconds)

      } and {
        val existsAfterDelete = (for {
          _ <- bucket.exists
          _ <- bucket.delete
          r <- s3.buckets.collect[List]()
        } yield r.exists(_.name == name))

        existsAfterDelete must beFalse.await(1, 10.seconds)
      } and (bucket.exists must beFalse.await(1, 10.seconds))
    }

    "Get content of file 'testfile.txt'" in withMatEx { implicit ee: EE =>
      val objRef = ceph.bucket(bucketName).obj("testfile.txt")

      objRef.get must beLike[objRef.RESTGetRequest] {
        case req => (req() runWith consume) aka "content" must beLike[String] {
          case resp =>
            resp.isEmpty must beFalse and (
              resp.startsWith("hello world !!!") aka "starts-with" must beTrue
            )
        }.await(2, 10.seconds)
      }
    }

    "Get partial content of a file" in withMatEx { implicit ee: EE =>
      (ceph.bucket(bucketName).obj("testfile.txt").
        get(range = Some(ByteRange(4, 9))) runWith consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in withMatEx { implicit ee: EE =>
      ceph.bucket(bucketName).obj("cabinet-test-folder/DoesNotExist.txt").
        get() runWith consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object cabinet-test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
    }

    "Write and copy file" in withMatEx { implicit ee: EE =>
      val file1 = ceph.bucket(bucketName).obj("testfile1.txt")
      val file2 = ceph.bucket(bucketName).obj("testfile2.txt")

      file1.exists must beFalse.await(1, 10.seconds) and {
        val upload = file1.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith upload }.flatMap(_ => file1.exists).
          aka(s"${file1.name} exists") must beTrue.await(1, 10.seconds)
      } and {
        file1.copyTo(file2).flatMap(_ => file2.exists) must beTrue.
          await(1, 10.seconds)
      } and {
        (for {
          _ <- Future.sequence(Seq(file1.delete, file2.delete))
          a <- file1.exists
          b <- file2.exists
        } yield a -> b) must beEqualTo(false -> false).await(1, 10.seconds)
      }
    }
  }
}

package tests

import scala.concurrent.Future
import scala.concurrent.duration._

import play.api.libs.iteratee.{ Enumerator, Iteratee }

import org.specs2.concurrent.{ ExecutionEnv => EE }

import com.zengularity.storage.{ Bucket, Bytes, ByteRange, Object }

import Enumerators.repeat
import TestUtils.{ WS, ceph, consume }

class S3CephSpec extends org.specs2.mutable.Specification {
  "S3 Ceph" title

  sequential

  "Ceph client" should {
    val bucketName = s"test-${System identityHashCode this}"

    "Access the system" in { implicit ee: EE =>
      val s3 = ceph
      val bucket = s3.bucket(bucketName)

      bucket.create().flatMap(_ => s3.buckets.collect[List]()).map(
        _.exists(_.name == bucketName)
      ) must beTrue.await(1, 10.seconds) and (
          bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds)
        )
    }

    "Create buckets and files" in { implicit ee: EE =>
      val s3 = ceph
      val name = s"test-${System identityHashCode s3}"

      val objects = for {
        _ <- s3.bucket(name).create()
        _ <- Enumerator("Hello".getBytes) |>>> s3.
          bucket(name).obj("testfile.txt").put[Array[Byte]]
        _ <- (s3.buckets() |>>> Iteratee.getChunks[Bucket])
        o <- (s3.bucket(name).objects() |>>> Iteratee.getChunks[Object])
      } yield o

      objects must beLike[List[Object]] {
        case list =>
          list.find(_.name == "testfile.txt") must beSome
      }.await(1, 10.seconds)
    }

    "List of all buckets" in { implicit ee: EE =>
      ceph.buckets.collect[List]().
        map(_.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
    }

    s"Write file in $bucketName bucket using multipart" >> {
      "with the default maximum" in { implicit ee: EE =>
        val filetest = ceph.bucket(bucketName).obj("testfile.txt")
        val body = List.fill(1000)("hello world !!!").mkString(" ").getBytes
        type PutReq = filetest.RESTPutRequest[Array[Byte], Unit]

        filetest.put[Array[Byte], Unit].aka("request") must beLike[PutReq] {
          case req =>
            val upload = req({})((_, _) => Future.successful({}))

            (repeat(20) { body } |>>> upload).
              flatMap(_ => filetest.exists) must beTrue.await(1, 10.seconds)
        }
      }

      "with a maximum of 2 parts instead of 3" in { implicit ee: EE =>
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

        (body |>>> upload).flatMap(_ => obj.exists).
          aka("upload") must beTrue.await(2, 20.seconds) and {
            partCount must_== 2
          }
      }
    }

    s"Get contents of $bucketName bucket" in { implicit ee: EE =>
      ceph.bucket(bucketName).objects.collect[List]().map(
        _.exists(_.name == "testfile.txt")
      ) must beTrue.await(1, 10.seconds)
    }

    "Create & delete buckets" in { implicit ee: EE =>
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

    "Get content of a file" in { implicit ee: EE =>
      val objRef = ceph.bucket(bucketName).obj("testfile.txt")

      objRef.get must beLike[objRef.RESTGetRequest] {
        case req => (req() |>>> consume) aka "content" must beLike[String] {
          case resp => resp.isEmpty must beFalse and (
            resp.startsWith("hello world !!!") must beTrue
          )
        }.await(1, 10.seconds)
      }
    }

    "Get partial content of a file" in { implicit ee: EE =>
      (ceph.bucket(bucketName).obj("testfile.txt").
        get(range = Some(ByteRange(4, 9))) |>>> consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in { implicit ee: EE =>
      ceph.bucket(bucketName).obj("test-folder/DoesNotExist.txt").
        get() |>>> consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
    }

    "Write and copy file" in { implicit ee: EE =>
      val file1 = ceph.bucket(bucketName).obj("testfile1.txt")
      val file2 = ceph.bucket(bucketName).obj("testfile2.txt")

      file1.exists must beFalse.await(1, 10.seconds) and {
        val iteratee = file1.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } |>>> iteratee }.flatMap(_ => file1.exists).
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

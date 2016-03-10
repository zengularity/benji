package tests

import org.specs2.mutable.Specification
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import org.specs2.concurrent.{ ExecutionEnv => EE }

import Enumerators.repeat
import TestUtils.{ WS, aws, consume }

object S3AwsSpec extends Specification with AwsTests {
  "S3 Amazon" title

  sequential

  awsSuite("in path style", TestUtils.aws(_))

  awsSuite("in virtual host style", TestUtils.awsVirtualHost(_))
}

sealed trait AwsTests { specs: Specification =>
  def awsSuite(
    label: String,
    s3f: ExecutionContext => com.zengularity.s3.WSS3
  ) = s"S3 client $label" should {
    val bucketName = s"test-${System identityHashCode s3f}"

    s"Not find bucket $bucketName before it's created" in {
      implicit ee: EE =>
        val bucket = s3f(ee.ec).bucket(bucketName)

        bucket.exists must beFalse.await(retries = 1, timeout = 10.seconds)
    }

    s"Creating bucket $bucketName and get a list of all buckets" in {
      implicit ee: EE =>
        val s3 = s3f(ee.ec)
        val bucket = s3.bucket(bucketName)

        bucket.create().flatMap(_ => s3.buckets).
          map(_.exists(_.name == bucketName)) must beTrue.
          await(retries = 1, timeout = 10.seconds) and (
            bucket.create(checkBefore = true) must beFalse.
            await(retries = 1, timeout = 10.seconds)
          )
    }

    s"Get objects of the empty $bucketName bucket" in {
      implicit ee: EE =>
        val s3 = s3f(ee.ec)
        s3.bucket(bucketName).objects.map(_.size).
          aka("object count") must beEqualTo(0).
          await(retries = 1, timeout = 5.seconds)
    }

    s"Write file in $bucketName bucket" in { implicit ee: EE =>
      val s3 = s3f(ee.ec)
      val filetest = s3.bucket(bucketName).obj("testfile.txt")
      val upload = filetest.put[Array[Byte], Long](0L) { (sz, chunk) =>
        Future.successful(sz + chunk.size)
      }
      val body = List.fill(1000)("hello world !!!").mkString(" ").getBytes

      (repeat(20)(body) |>>> upload).
        flatMap(sz => filetest.exists.map(sz -> _)).
        aka("exists") must beEqualTo(319980 -> true).
        await(retries = 1, timeout = 10.seconds)
    }

    s"Get contents of $bucketName bucket after first upload" in {
      implicit ee: EE =>
        val s3 = s3f(ee.ec)
        s3.bucket(bucketName).objects.map(
          _.exists(_.key == "testfile.txt")
        ) must beTrue.await(retries = 1, timeout = 5.seconds)
    }

    "Creating & deleting buckets" in { implicit ee: EE =>
      val name = s"removable-${System identityHashCode s3f}"
      val s3 = s3f(ee.ec)
      val bucket = s3.bucket(name)

      bucket.exists must beFalse.await(retries = 1, timeout = 5.seconds) and {
        val bucketsWithTestRemovable = bucket.create().flatMap(_ => s3.buckets)
        bucketsWithTestRemovable.map(_.exists(_.name == name)).
          aka("exists") must beTrue.await(retries = 1, timeout = 5.seconds)

      } and {
        (for {
          _ <- bucket.exists
          _ <- bucket.delete
          bs <- s3.buckets
        } yield bs.exists(_.name == name)).aka("exists") must beFalse.
          await(retries = 1, timeout = 5.seconds) and (
            bucket.exists must beFalse.await(retries = 1, timeout = 5.seconds)
          )
      }
    }

    "Get contents of a file" in { implicit ee: EE =>
      val s3 = s3f(ee.ec)

      (s3.bucket(bucketName).obj("testfile.txt").get |>>> consume).
        aka("response") must beLike[String]({
          case response =>
            response.isEmpty must beFalse and (
              response.startsWith("hello world !!!") must beTrue
            )
        }).await(retries = 1, timeout = 10.seconds)
    }

    "Get contents of a non-existing file" in { implicit ee: EE =>
      val s3 = s3f(ee.ec)

      s3.bucket(bucketName).obj("test-folder/DoesNotExist.txt").
        get |>>> consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(retries = 1, timeout = 10.seconds)
    }

    "Write and copy file" in { implicit ee: EE =>
      val s3 = s3f(ee.ec)

      val file1 = s3.bucket(bucketName).obj("testfile1.txt")
      val file2 = s3.bucket(bucketName).obj("testfile2.txt")

      file1.exists.aka("exists #1") must beFalse.
        await(retries = 1, timeout = 5.seconds) and {
          val iteratee = file1.put[Array[Byte]]
          val body = List.fill(1000)("qwerty").mkString(" ").getBytes

          { repeat(20) { body } |>>> iteratee }.flatMap(_ => file1.exists).
            aka("exists") must beTrue.await(retries = 1, timeout = 10.seconds)

        } and {
          file1.copyTo(file2).flatMap(_ => file2.exists) must beTrue.
            await(retries = 1, timeout = 10.seconds)
        } and {
          (for {
            _ <- file1.delete
            _ <- file2.delete
            a <- file1.exists
            b <- file2.exists
          } yield a -> b) must beEqualTo(false -> false).
            await(retries = 1, timeout = 10.seconds)
        }
    }
  }
}

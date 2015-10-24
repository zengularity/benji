package tests

import scala.concurrent.duration._

import org.specs2.concurrent.{ ExecutionEnv => EE }

import Enumerators.repeat
import TestUtils.{ aws, consume }

object S3AwsSpec extends org.specs2.mutable.Specification {
  "S3 Amazon" title

  sequential

  "S3 client" should {
    val bucketName = s"bucket-${System identityHashCode this}"

    s"Creating bucket $bucketName and get a list of all buckets" in {
      implicit ee: EE =>
        val s3 = aws
        val bucket = aws.bucket(bucketName)

        bucket.create.flatMap(_ => aws.buckets).
          map(_.exists(_.name == bucketName)) must beTrue.
          await(retries = 1, timeout = 10.seconds)
    }

    s"Write file in $bucketName bucket" in {
      implicit ee: EE =>
        val filetest = aws.bucket(bucketName).obj("testfile.txt")
        val iteratee = filetest.put[Array[Byte]]
        val body = List.fill(1000)("hello world !!!").mkString(" ").getBytes

        { repeat(20) { body } |>>> iteratee }.flatMap { _ => filetest.exists }.
          aka("exists") must beTrue.await(retries = 1, timeout = 10.seconds)
    }

    s"Get contents of $bucketName bucket" in {
      implicit ee: EE =>
        aws.bucket(bucketName).objects.map(
          _.exists(_.key == "testfile.txt")
        ) must beTrue.await(retries = 1, timeout = 5.seconds)
    }

    "Creating & deleting buckets" in { implicit ee: EE =>
      val s3 = aws
      val name = s"removable-${System identityHashCode s3}"
      val bucket = s3.bucket(name)

      bucket.exists must beFalse.await(retries = 1, timeout = 5.seconds) and {
        val bucketsWithTestRemovable = bucket.create.flatMap(_ => s3.buckets)
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
      (aws.bucket(bucketName).obj("testfile.txt").get |>>> consume).
        aka("response") must beLike[String]({
          case response =>
            response.isEmpty must beFalse and (
              response.startsWith("hello world !!!") must beTrue
            )
        }).await(retries = 1, timeout = 10.seconds)
    }

    "Get contents of a non-existing file" in { implicit ee: EE =>
      aws.bucket(bucketName).obj("test-folder/DoesNotExist.txt").
        get |>>> consume must throwA[IllegalStateException].like({
          case e => e.getMessage must_== s"Could not get the contents of the object test-folder/DoesNotExist.txt in the bucket $bucketName. Response (404)"
        }).await(retries = 1, timeout = 10.seconds)
    }

    "Write and copy file" in { implicit ee: EE =>
      val file1 = aws.bucket(bucketName).obj("testfile1.txt")
      val file2 = aws.bucket(bucketName).obj("testfile2.txt")

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

package tests

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult
import org.specs2.concurrent.{ ExecutionEnv => EE }

import com.zengularity.storage.{ Bucket, Object }
import com.zengularity.s3.WSS3ObjectRef

import Sources.repeat
import TestUtils.{ WS, consume, withMatEx }

class S3AwsSpec extends Specification with AwsTests {
  "S3 Amazon" title

  sequential

  awsSuite(
    "in path style",
    TestUtils.aws
  )(TestUtils.materializer)

  awsSuite(
    "in virtual host style",
    TestUtils.awsVirtualHost
  )(TestUtils.materializer)
}

sealed trait AwsTests { specs: Specification =>
  def awsSuite(
    label: String,
    s3f: => com.zengularity.s3.WSS3
  )(implicit m: Materializer) = s"S3 client $label" should {
    val bucketName = s"cabinet-test-${System identityHashCode s3f}"

    s"Not find bucket $bucketName before it's created" in withMatEx {
      implicit ee: EE =>
        val bucket = s3f.bucket(bucketName)

        bucket.exists must beFalse.await(1, 10.seconds)
    }

    s"Creating bucket $bucketName and get a list of all buckets" in withMatEx {
      implicit ee: EE =>
        val s3 = s3f
        val bucket = s3.bucket(bucketName)

        bucket.create().flatMap(_ => s3.buckets.collect[List]()).
          map(_.exists(_.name == bucketName)) must beTrue.
          await(1, 10.seconds) and (
            bucket.create(checkBefore = true) must beFalse.
            await(1, 10.seconds)
          )
    }

    s"Get objects of the empty $bucketName bucket" in withMatEx {
      implicit ee: EE =>
        val s3 = s3f
        s3.bucket(bucketName).objects.
          collect[List]().map(_.size) must beEqualTo(0).
          await(retries = 1, timeout = 5.seconds)
    }

    s"Write file in $bucketName bucket" in withMatEx { implicit ee: EE =>
      val s3 = s3f
      val filetest = s3.bucket(bucketName).obj("testfile.txt")
      val upload = filetest.put[Array[Byte], Long](0L) { (sz, chunk) =>
        Future.successful(sz + chunk.size)
      }
      val body = List.fill(1000)("hello world !!!").mkString(" ").getBytes

      (repeat(20)(body) runWith upload).
        flatMap(sz => filetest.exists.map(sz -> _)).
        aka("exists") must beEqualTo(319980 -> true).await(1, 10.seconds)
    }

    s"Get contents of $bucketName bucket after first upload" in withMatEx {
      implicit ee: EE =>
        val s3 = s3f
        def ls = s3.bucket(bucketName).objects()

        (ls runWith Sink.seq[Object]).map(
          _.exists(_.name == "testfile.txt")
        ) must beTrue.await(1, 5.seconds)
    }

    "Creating & deleting buckets" in withMatEx { implicit ee: EE =>
      val name = s"cabinet-test-removable-${System identityHashCode s3f}"
      val s3 = s3f
      val bucket = s3.bucket(name)

      bucket.exists must beFalse.await(1, 5.seconds) and {
        val bucketsWithTestRemovable = bucket.create().
          flatMap(_ => s3.buckets.collect[List]())

        bucketsWithTestRemovable.map(_.exists(_.name == name)).
          aka("exists") must beTrue.await(1, 5.seconds)

      } and {
        (for {
          _ <- bucket.exists
          _ <- bucket.delete
          _ <- bucket.delete.filter(_ => false).recoverWith {
            case _: IllegalStateException => Future.successful({})
          }
          bs <- (s3.buckets() runWith Sink.seq[Bucket])
        } yield bs.exists(_.name == name)).aka("exists") must beFalse.
          await(1, 5.seconds) and (
            bucket.exists must beFalse.await(1, 5.seconds)
          )
      }
    }

    "Get contents of a file" in withMatEx { implicit ee: EE =>
      val s3 = s3f

      (s3.bucket(bucketName).obj("testfile.txt").get() runWith consume).
        aka("response") must beLike[String]({
          case response =>
            response.isEmpty must beFalse and (
              response.startsWith("hello world !!!") must beTrue
            )
        }).await(1, 10.seconds)
    }

    "Get contents of a non-existing file" in withMatEx { implicit ee: EE =>
      val s3 = s3f

      s3.bucket(bucketName).obj("cabinet-test-folder/DoesNotExist.txt").
        get() runWith consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object cabinet-test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(retries = 1, timeout = 10.seconds)
    }

    "Write and delete file" in { implicit ee: EE =>
      val s3 = s3f
      val file = s3.bucket(bucketName).obj("removable.txt")

      file.exists.aka("exists #1") must beFalse.await(1, 5.seconds) and {
        val put = file.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(5) { body } runWith put }.flatMap(_ => file.exists).
          aka("exists") must beTrue.await(1, 10.seconds)

      } and {
        (for {
          _ <- file.delete
          _ <- file.delete.filter(_ => false).recoverWith {
            case _: IllegalArgumentException => Future.successful({})
            case err                         => Future.failed[Unit](err)
          }
          x <- file.exists
        } yield x) must beFalse.await(1, 10.seconds)
      }
    }

    "Write and copy file" in withMatEx { implicit ee: EE =>
      val s3 = s3f

      val file1 = s3.bucket(bucketName).obj("testfile1.txt")
      val file2 = s3.bucket(bucketName).obj("testfile2.txt")

      file1.exists.aka("exists #1") must beFalse.await(1, 5.seconds) and {
        val put = file1.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith put }.flatMap(_ => file1.exists).
          aka("exists") must beTrue.await(1, 10.seconds)

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

    "Write and move file" >> {
      lazy val s3 = s3f

      def moveSpec[T](target: => Future[WSS3ObjectRef], preventOverwrite: Boolean = true)(onMove: (WSS3ObjectRef, WSS3ObjectRef, Future[Unit]) => MatchResult[Future[T]])(implicit ee: EE) = {
        val file3 = s3.bucket(bucketName).obj("testfile3.txt")

        file3.exists.aka("exists #3") must beFalse.await(1, 5.seconds) and (
          target must beLike[WSS3ObjectRef] {
            case file4 =>
              val write = file3.put[Array[Byte]]
              val body = List.fill(1000)("qwerty").mkString(" ").getBytes

              { repeat(20) { body } runWith write }.flatMap(_ => file3.exists).
                aka("exists") must beTrue.await(1, 10.seconds) and {
                  onMove(file3, file4, file3.moveTo(file4, preventOverwrite))
                } and {
                  (for {
                    _ <- file3.delete.recoverWith {
                      case _ => Future.successful({})
                    }
                    _ <- file4.delete
                    a <- file3.exists
                    b <- file4.exists
                  } yield a -> b) must beEqualTo(false -> false).
                    await(1, 10.seconds)
                }
          }.await(1, 10.seconds)
        )
      }

      @inline def successful(file3: WSS3ObjectRef, file4: WSS3ObjectRef, res: Future[Unit])(implicit ee: EE) = (for {
        _ <- res
        a <- file3.exists
        b <- file4.exists
      } yield a -> b) must beEqualTo(false -> true).await(1, 10.seconds)

      @inline def failed(file3: WSS3ObjectRef, file4: WSS3ObjectRef, res: Future[Unit])(implicit ee: EE) = (res.recoverWith {
        case _: IllegalStateException => for {
          a <- file3.exists
          b <- file4.exists
        } yield a -> b
      }) must beEqualTo(true -> true).await(1, 10.seconds)

      @inline def existingTarget(implicit ee: EE): Future[WSS3ObjectRef] = {
        val target = s3.bucket(bucketName).obj("testfile4.txt")
        val write = target.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith write }.map(_ => target)
      }

      "if prevent overwrite when target doesn't exist" in {
        implicit ee: EE =>
          moveSpec(
            Future.successful(s3.bucket(bucketName).obj("testfile4.txt"))
          )(successful)
      }

      "if prevent overwrite when target exists" in { implicit ee: EE =>
        moveSpec(existingTarget)(failed)
      }

      "if overwrite when target exists" in { implicit ee: EE =>
        moveSpec(existingTarget, preventOverwrite = false)(successful)
      }
    }
  }
}

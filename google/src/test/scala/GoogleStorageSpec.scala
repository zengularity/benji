package tests.benji.google

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.contrib.TestKit.assertAllStagesStopped

import play.api.libs.ws.DefaultBodyWritables._

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult

import com.zengularity.benji.{ Bucket, ByteRange, Object }
import com.zengularity.benji.google.GoogleObjectRef

class GoogleStorageSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification {

  import TestUtils.{ google, googleTransport, consume }
  import Sources.repeat

  "Google Cloud Storage" title

  sequential

  implicit def materializer: Materializer = TestUtils.materializer

  "Client" should {
    val bucketName = s"benji-test-${System identityHashCode this}"
    val objName = "testfile.txt"

    "Access the system" in {
      val bucket = google.bucket(bucketName)

      bucket.toString must_== s"GoogleBucketRef($bucketName)" and {
        bucket.create().flatMap(_ => google.buckets.collect[List]()).map(
          _.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
      } and (
        bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds))
    }

    "Create buckets and files" in assertAllStagesStopped {
      val name = s"benji-test-${System identityHashCode google}"
      val objects = for {
        _ <- google.bucket(name).create()
        _ <- Source.single("Hello".getBytes) runWith google.bucket(name).obj(objName).put[Array[Byte]]
        _ <- (google.buckets() runWith Sink.seq[Bucket])
        o <- (google.bucket(name).objects() runWith Sink.seq[Object])
      } yield o

      objects must beLike[Seq[Object]] {
        case list => list.find(_.name == objName) must beSome
      }.await(1, 10.seconds)
    }

    "List of all buckets" in {
      google.buckets.collect[List]().
        map(_.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
    }

    val fileStart = "hello world !!!"

    val partCount = 3
    s"Write file in $bucketName bucket using $partCount parts" in {
      val filetest = google.bucket(bucketName).obj(objName)
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

    s"Get contents of $bucketName bucket" in {
      google.bucket(bucketName).objects.collect[List]().map(
        _.exists(_.name == objName)) must beTrue.await(1, 10.seconds)
    }

    "Create & delete buckets" in {
      val name = s"benji-test-2${System identityHashCode google}"
      val bucket = google.bucket(name)

      bucket.exists aka "exists #1" must beFalse.
        await(1, 10.seconds) and {
          val bucketsWithTestRemovable =
            bucket.create().flatMap(_ => google.buckets.collect[List]())

          bucketsWithTestRemovable.map(
            _.exists(_.name == name)) aka "exists #2" must beTrue.await(1, 10.seconds)
        } and {
          val existsAfterDelete = (for {
            _ <- bucket.exists
            _ <- bucket.delete
            r <- google.buckets.collect[List]()
          } yield r.exists(_.name == name))

          existsAfterDelete aka "after delete" must beFalse.
            await(1, 10.seconds)
        } and (bucket.exists aka "exists #3" must beFalse.await(1, 10.seconds))
    }

    s"Metadata of a file $objName" in assertAllStagesStopped {
      val objRef = google.bucket(bucketName).obj(objName)

      objRef.headers() must beLike[Map[String, Seq[String]]] {
        case headers => headers.get("metadata.foo") must beSome(Seq("bar"))
      }.await(1, 5.seconds)
    }

    s"Get content of a file $objName" in assertAllStagesStopped {
      val objRef = google.bucket(bucketName).obj(objName)

      objRef.toString must beEqualTo(
        s"GoogleObjectRef($bucketName, testfile.txt)") and {
          objRef.get must beLike[objRef.GoogleGetRequest] {
            case req =>
              (req() runWith consume) aka "content" must beLike[String] {
                case resp => resp.isEmpty must beFalse and (
                  resp.startsWith(fileStart) must beTrue)
              }.await(1, 10.seconds)
          }
        }
    }

    "Get partial content of a file" in assertAllStagesStopped {
      (google.bucket(bucketName).obj(objName).
        get(range = Some(ByteRange(4, 9))) runWith consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in assertAllStagesStopped {
      google.bucket(bucketName).obj("test-folder/DoesNotExist.txt").
        get() runWith consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
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
          _ <- Future.sequence(Seq(file1.delete, file2.delete))
          a <- file1.exists
          b <- file2.exists
        } yield a -> b) must beEqualTo(false -> false).await(1, 10.seconds)
      }
    }

    "Write and delete file" in assertAllStagesStopped {
      val file = google.bucket(bucketName).obj("removable.txt")

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
            case err => Future.failed[Unit](err)
          }
          x <- file.exists
        } yield x) must beFalse.await(1, 10.seconds)
      }
    }

    "Write and move file" >> {
      def moveSpec[T](target: => Future[GoogleObjectRef], preventOverwrite: Boolean = true)(onMove: (GoogleObjectRef, GoogleObjectRef, Future[Unit]) => MatchResult[Future[T]]) = {
        val file3 = google.bucket(bucketName).obj("testfile3.txt")

        file3.exists.aka("exists #3") must beFalse.await(1, 5.seconds) and (
          target must beLike[GoogleObjectRef] {
            case file4 =>
              val write = file3.put[Array[Byte]]
              val body = List.fill(1000)("qwerty").mkString(" ").getBytes

              { repeat(20) { body } runWith write }.flatMap(_ => file3.exists).
                aka("exists") must beTrue.await(1, 10.seconds) and {
                  onMove(file3, file4, file3.moveTo(file4, preventOverwrite))
                } and {
                  (for {
                    _ <- file3.delete.recoverWith {
                      case _: IllegalArgumentException =>
                        Future.successful({})
                      case err => Future.failed[Unit](err)
                    }
                    _ <- file4.delete
                    a <- file3.exists
                    b <- file4.exists
                  } yield a -> b) must beEqualTo(false -> false).
                    await(1, 10.seconds)
                }
          }.await(1, 10.seconds))
      }

      @inline def successful(file3: GoogleObjectRef, file4: GoogleObjectRef, res: Future[Unit]) = (for {
        _ <- res
        a <- file3.exists
        b <- file4.exists
      } yield a -> b) must beEqualTo(false -> true).await(1, 10.seconds)

      @inline def failed(file3: GoogleObjectRef, file4: GoogleObjectRef, res: Future[Unit]) = (res.recoverWith {
        case _: IllegalStateException => for {
          a <- file3.exists
          b <- file4.exists
        } yield a -> b
      }) must beEqualTo(true -> true).await(1, 10.seconds)

      @inline def existingTarget: Future[GoogleObjectRef] = {
        val target = google.bucket(bucketName).obj("testfile4.txt")
        val write = target.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith write }.map(_ => target)
      }

      "if prevent overwrite when target doesn't exist" in assertAllStagesStopped {
        moveSpec(
          Future.successful(google.bucket(bucketName).obj("testfile4.txt")))(successful)
      }

      "if prevent overwrite when target exists" in assertAllStagesStopped {
        moveSpec(existingTarget)(failed)
      }

      "if overwrite when target exists" in assertAllStagesStopped {
        moveSpec(existingTarget, preventOverwrite = false)(successful)
      }
    }
  }
}

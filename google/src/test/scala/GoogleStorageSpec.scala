package tests

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import play.api.libs.iteratee.{ Enumerator, Iteratee }

import org.specs2.concurrent.{ ExecutionEnv => EE }
import org.specs2.matcher.MatchResult

import com.zengularity.storage.{ Bucket, Bytes, ByteRange, Object }
import com.zengularity.google.GoogleObjectRef

import Enumerators.repeat

import TestUtils.{ google, googleTransport, consume }

class GoogleStorageSpec extends org.specs2.mutable.Specification {
  "Google Cloud Storage" title

  sequential

  "Google client" should {
    val bucketName = s"cabinet-test-${System identityHashCode this}"

    "Access the system" in { implicit ee: EE =>
      val bucket = google.bucket(bucketName)

      bucket.create().flatMap(_ => google.buckets.collect[List]()).map(
        _.exists(_.name == bucketName)
      ) must beTrue.await(1, 10.seconds) and (
          bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds)
        )
    }

    "Create buckets and files" in { implicit ee: EE =>
      val name = s"cabinet-test-${System identityHashCode google}"
      val objects = for {
        _ <- google.bucket(name).create()
        _ <- Enumerator("Hello".getBytes) |>>> google.
          bucket(name).obj("testfile.txt").put[Array[Byte]]
        _ <- (google.buckets() |>>> Iteratee.getChunks[Bucket])
        o <- (google.bucket(name).objects() |>>> Iteratee.getChunks[Object])
      } yield o

      objects must beLike[List[Object]] {
        case list => list.find(_.name == "testfile.txt") must beSome
      }.await(1, 10.seconds)
    }

    "List of all buckets" in { implicit ee: EE =>
      google.buckets.collect[List]().
        map(_.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
    }

    val fileStart = "hello world !!!"

    val partCount = 3
    s"Write file in $bucketName bucket using $partCount parts" in {
      implicit ee: EE =>
        val filetest = google.bucket(bucketName).obj("testfile.txt")
        var b = 0.toByte
        def nextByte = {
          b = (b + 1).toByte
          b
        }

        def body = fileStart.getBytes("UTF-8") ++ Array.fill(
          GoogleObjectRef.defaultThreshold.bytes.toInt - 3
        )(nextByte) ++ "XXX".getBytes("UTF-8")

        filetest.put[Array[Byte], Unit].
          aka("request") must beLike[filetest.RESTPutRequest[Array[Byte], Unit]] {
            case req =>
              val upload = req({})((_, _) => Future.successful({}))

              (repeat(partCount - 1)(body).andThen(Enumerator(
                body.drop(10) ++ "ZZZ".getBytes("UTF-8")
              )) |>>> upload).
                flatMap { _ => filetest.exists } must beTrue.await(1, 10.seconds)
          }
    }

    s"Get contents of $bucketName bucket" in { implicit ee: EE =>
      google.bucket(bucketName).objects.collect[List]().map(
        _.exists(_.name == "testfile.txt")
      ) must beTrue.await(1, 10.seconds)
    }

    "Create & delete buckets" in { implicit ee: EE =>
      val name = s"test2-${System identityHashCode google}"
      val bucket = google.bucket(name)

      bucket.exists aka "exists #1" must beFalse.
        await(1, 10.seconds) and {
          val bucketsWithTestRemovable =
            bucket.create().flatMap(_ => google.buckets.collect[List]())

          bucketsWithTestRemovable.map(
            _.exists(_.name == name)
          ) aka "exists #2" must beTrue.await(1, 10.seconds)
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

    "Get content of a file" in { implicit ee: EE =>
      val objRef = google.bucket(bucketName).obj("testfile.txt")

      objRef.get must beLike[objRef.GoogleGetRequest] {
        case req => (req() |>>> consume) aka "content" must beLike[String] {
          case resp => resp.isEmpty must beFalse and (
            resp.startsWith(fileStart) must beTrue
          )
        }.await(1, 10.seconds)
      }
    }

    "Get partial content of a file" in { implicit ee: EE =>
      (google.bucket(bucketName).obj("testfile.txt").
        get(range = Some(ByteRange(4, 9))) |>>> consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in { implicit ee: EE =>
      google.bucket(bucketName).obj("cabinet-test-folder/DoesNotExist.txt").
        get() |>>> consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object cabinet-test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
    }

    "Write and copy files (w/o GZip compression)" in { implicit ee: EE =>
      val woGzip = google.withDisabledGZip(true)
      val file1 = woGzip.bucket(bucketName).obj("testfile1.txt")
      val file2 = woGzip.bucket(bucketName).obj("testfile2.txt")

      file1.exists must beFalse.await(1, 10.seconds) and {
        val iteratee = file1.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } |>>> iteratee }.flatMap(_ => file1.exists).
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

    "Write and delete file" in { implicit ee: EE =>
      val file = google.bucket(bucketName).obj("removable.txt")

      file.exists.aka("exists #1") must beFalse.await(1, 5.seconds) and {
        val iteratee = file.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(5) { body } |>>> iteratee }.flatMap(_ => file.exists).
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

    "Write and move file" >> {
      def moveSpec[T](target: => Future[GoogleObjectRef], preventOverwrite: Boolean = true)(onMove: (GoogleObjectRef, GoogleObjectRef, Future[Unit]) => MatchResult[Future[T]])(implicit ee: EE) = {
        val file3 = google.bucket(bucketName).obj("testfile3.txt")

        file3.exists.aka("exists #3") must beFalse.await(1, 5.seconds) and (
          target must beLike[GoogleObjectRef] {
            case file4 =>
              val write = file3.put[Array[Byte]]
              val body = List.fill(1000)("qwerty").mkString(" ").getBytes

              { repeat(20) { body } |>>> write }.flatMap(_ => file3.exists).
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
          }.await(1, 10.seconds)
        )
      }

      @inline def successful(file3: GoogleObjectRef, file4: GoogleObjectRef, res: Future[Unit])(implicit ee: EE) = (for {
        _ <- res
        a <- file3.exists
        b <- file4.exists
      } yield a -> b) must beEqualTo(false -> true).await(1, 10.seconds)

      @inline def failed(file3: GoogleObjectRef, file4: GoogleObjectRef, res: Future[Unit])(implicit ee: EE) = (res.recoverWith {
        case _: IllegalStateException => for {
          a <- file3.exists
          b <- file4.exists
        } yield a -> b
      }) must beEqualTo(true -> true).await(1, 10.seconds)

      @inline def existingTarget(implicit ec: ExecutionContext): Future[GoogleObjectRef] = {
        val target = google.bucket(bucketName).obj("testfile4.txt")
        val write = target.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } |>>> write }.map(_ => target)
      }

      "if prevent overwrite when target doesn't exist" in {
        implicit ee: EE =>
          moveSpec(
            Future.successful(google.bucket(bucketName).obj("testfile4.txt"))
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

package tests

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import scala.collection.immutable.Seq

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import org.specs2.concurrent.{ ExecutionEnv => EE }
import org.specs2.matcher.MatchResult

import com.zengularity.storage.{ Bucket, ByteRange, Object }
import com.zengularity.vfs.VFSObjectRef

import Sources.repeat

import TestUtils.{ vfs, vfsTransport, consume }

class VFSStorageSpec extends org.specs2.mutable.Specification {
  "VFS Cloud Storage" title

  sequential

  implicit def materializer: Materializer = TestUtils.materializer

  "VFS client" should {
    val bucketName = s"cabinet-test-${System identityHashCode this}"

    "Access the system" in { implicit ee: EE =>
      val bucket = vfs.bucket(bucketName)

      bucket.toString must_== s"VFSBucketRef($bucketName)" and {
        bucket.create().flatMap(_ => vfs.buckets.collect[List]()).map(
          _.exists(_.name == bucketName)
        ) must beTrue.await(1, 10.seconds)
      } and (
        bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds)
      )
    }

    "Create buckets and files" in { implicit ee: EE =>
      val name = s"cabinet-test-${System identityHashCode vfs}"
      val objects = for {
        _ <- vfs.bucket(name).create()
        _ <- Source.single("Hello".getBytes) runWith vfs.
          bucket(name).obj("testfile.txt").put[Array[Byte]]
        _ <- (vfs.buckets() runWith Sink.seq[Bucket])
        o <- (vfs.bucket(name).objects() runWith Sink.seq[Object])
      } yield o

      objects must beLike[Seq[Object]] {
        case list => list.find(_.name == "testfile.txt") must beSome
      }.await(1, 10.seconds)
    }

    "List of all buckets" in { implicit ee: EE =>
      vfs.buckets.collect[List]().
        map(_.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
    }

    val fileStart = "hello world !!!"

    val partCount = 3
    s"Write file in $bucketName bucket using $partCount parts" in {
      implicit ee: EE =>
        val filetest = vfs.bucket(bucketName).obj("testfile.txt")
        var b = 0.toByte
        def nextByte = {
          b = (b + 1).toByte
          b
        }

        def body = fileStart.getBytes("UTF-8") ++ Array.fill(
          VFSObjectRef.defaultThreshold.bytes.toInt - 3
        )(nextByte) ++ "XXX".getBytes("UTF-8")

        filetest.put[Array[Byte], Unit].
          aka("request") must beLike[filetest.RESTPutRequest[Array[Byte], Unit]] {
            case req =>
              val upload = req({})((_, _) => Future.successful({}))

              (repeat(partCount - 1)(body).++(Source.single(
                body.drop(10) ++ "ZZZ".getBytes("UTF-8")
              )) runWith upload).
                flatMap { _ => filetest.exists } must beTrue.
                await(1, 10.seconds)
          }
    }

    s"Get contents of $bucketName bucket" in { implicit ee: EE =>
      vfs.bucket(bucketName).objects.collect[List]().map(
        _.exists(_.name == "testfile.txt")
      ) must beTrue.await(1, 10.seconds)
    }

    "Create & delete buckets" in { implicit ee: EE =>
      val name = s"cabinet-test-2${System identityHashCode vfs}"
      val bucket = vfs.bucket(name)

      bucket.exists aka "exists #1" must beFalse.
        await(1, 10.seconds) and {
          val bucketsWithTestRemovable =
            bucket.create().flatMap(_ => vfs.buckets.collect[List]())

          bucketsWithTestRemovable.map(
            _.exists(_.name == name)
          ) aka "exists #2" must beTrue.await(1, 10.seconds)
        } and {
          val existsAfterDelete = (for {
            _ <- bucket.exists
            _ <- bucket.delete
            r <- vfs.buckets.collect[List]()
          } yield r.exists(_.name == name))

          existsAfterDelete aka "after delete" must beFalse.
            await(1, 10.seconds)
        } and (bucket.exists aka "exists #3" must beFalse.await(1, 10.seconds))
    }

    "Get content of a file" in { implicit ee: EE =>
      val objRef = vfs.bucket(bucketName).obj("testfile.txt")

      objRef.toString must beEqualTo(
        s"VFSObjectRef($bucketName, testfile.txt)"
      ) and {
          objRef.get must beLike[objRef.VFSGetRequest] {
            case req =>
              (req() runWith consume) aka "content" must beLike[String] {
                case resp => resp.isEmpty must beFalse and (
                  resp.startsWith(fileStart) must beTrue
                )
              }.await(1, 10.seconds)
          }
        }
    }

    "Get partial content of a file" in { implicit ee: EE =>
      (vfs.bucket(bucketName).obj("testfile.txt"). // hello world !!!
        get(range = Some(ByteRange(4, 9))) runWith consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in { implicit ee: EE =>
      vfs.bucket(bucketName).obj("test-folder/DoesNotExist.txt").
        get() runWith consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
    }

    "Write and copy files" in { implicit ee: EE =>
      val file1 = vfs.bucket(bucketName).obj("testfile1.txt")
      val file2 = vfs.bucket(bucketName).obj("testfile2.txt")

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

    "Write and delete file" in { implicit ee: EE =>
      val file = vfs.bucket(bucketName).obj("removable.txt")

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

    "Write and move file" >> {
      def moveSpec[T](target: => Future[VFSObjectRef], preventOverwrite: Boolean = true)(onMove: (VFSObjectRef, VFSObjectRef, Future[Unit]) => MatchResult[Future[T]])(implicit ee: EE) = {
        val file3 = vfs.bucket(bucketName).obj("testfile3.txt")

        file3.exists.aka("exists #3") must beFalse.await(1, 5.seconds) and (
          target must beLike[VFSObjectRef] {
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
          }.await(1, 10.seconds)
        )
      }

      @inline def successful(file3: VFSObjectRef, file4: VFSObjectRef, res: Future[Unit])(implicit ee: EE) = (for {
        _ <- res
        a <- file3.exists
        b <- file4.exists
      } yield a -> b) must beEqualTo(false -> true).await(1, 10.seconds)

      @inline def failed(file3: VFSObjectRef, file4: VFSObjectRef, res: Future[Unit])(implicit ee: EE) = (res.recoverWith {
        case _: IllegalStateException => for {
          a <- file3.exists
          b <- file4.exists
        } yield a -> b
      }) must beEqualTo(true -> true).await(1, 10.seconds)

      @inline def existingTarget(implicit ec: ExecutionContext): Future[VFSObjectRef] = {
        val target = vfs.bucket(bucketName).obj("testfile4.txt")
        val write = target.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith write }.map(_ => target)
      }

      "if prevent overwrite when target doesn't exist" in {
        implicit ee: EE =>
          moveSpec(
            Future.successful(vfs.bucket(bucketName).obj("testfile4.txt"))
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

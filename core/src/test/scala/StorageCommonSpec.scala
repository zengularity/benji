package tests.benji

import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.contrib.TestKit.assertAllStagesStopped
import akka.stream.scaladsl.Source

import play.api.libs.ws.BodyWritable

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult

import com.zengularity.benji.{ ByteRange, ObjectStorage, ObjectRef }

import scala.concurrent.Future

trait StorageCommonSpec extends BenjiMatchers { self: org.specs2.mutable.Specification =>
  import tests.benji.StreamUtils._

  def minimalCommonTests(storage: ObjectStorage, defaultBucketName: String)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv,
    writer: BodyWritable[Array[Byte]]) = {

    val bucketName = defaultBucketName

    sequential

    "Access the system" in assertAllStagesStopped {
      val bucket = storage.bucket(bucketName)

      {
        bucket must notExistsIn(storage)
      } and {
        bucket.create() must beTrue.await(1, 10.seconds)
      } and {
        bucket must existsIn(storage)
      } and {
        bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds)
      }
    }

    s"Lists objects of the empty $bucketName bucket" in assertAllStagesStopped {
      storage.bucket(bucketName).objects.collect[List]().map(_.size) must beEqualTo(0).await(1, 5.seconds)
    }

    s"Write file in $bucketName bucket" in assertAllStagesStopped {
      val bucket = storage.bucket(bucketName)
      val filename = "testfile.txt"
      val filetest = bucket.obj(filename)
      val put = filetest.put[Array[Byte], Long]
      val upload = put(0L, metadata = Map("foo" -> "bar")) { (sz, chunk) =>
        Future.successful(sz + chunk.size)
      }
      val body = List.fill(1000)("hello world !!!").mkString(" ").getBytes

      {
        filetest must notExistsIn(bucket)
      } and { // upload file
        (repeat(20)(body) runWith upload) must beEqualTo(319980).await(1, 10.seconds)
      } and {
        filetest must existsIn(bucket)
      }
    }

    "Get contents of file" in {
      (storage.bucket(bucketName).obj("testfile.txt").get() runWith consume).aka("response") must
        beLike[String]({ case response => response.startsWith("hello world !!!") must beTrue }).await(1, 10.seconds)
    }
  }

  def commonTests(storage: ObjectStorage, defaultBucketName: String)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv,
    writer: BodyWritable[Array[Byte]]) = {

    val bucketName = defaultBucketName

    sequential

    minimalCommonTests(storage, defaultBucketName)

    "Create & delete buckets" in assertAllStagesStopped {
      val name = s"benji-test-2${System identityHashCode storage}"
      val bucket = storage.bucket(name)

      {
        bucket must notExistsIn(storage)
      } and {
        bucket.create() must beTrue.await(1, 10.seconds)
      } and {
        bucket must existsIn(storage)
      } and {
        bucket.delete() must be_==({}).await(1, 10.seconds)
      } and {
        bucket must notExistsIn(storage)
      }
    }

    "Creating & deleting non-empty buckets" in assertAllStagesStopped {
      val name = s"benji-test-nonempty-${System identityHashCode storage}"
      val bucket = storage.bucket(name)
      val filetest = bucket.obj("testfile.txt")

      bucket must notExistsIn(storage) and {
        // creating bucket
        bucket.create() must beTrue.await(1, 5.seconds)
      } and {
        bucket must existsIn(storage)
      } and {
        // uploading file to bucket
        val put = filetest.put[Array[Byte], Long]
        val upload = put(0L, metadata = Map("foo" -> "bar")) { (sz, chunk) => Future.successful(sz + chunk.size) }
        val body = "hello world".getBytes()

        Source.single(body).runWith(upload).flatMap(sz => filetest.exists.map(sz -> _)).aka("exists") must
          beEqualTo(body.length -> true).await(1, 10.seconds)
      } and {
        // checking that the upload operation is effective
        // because otherwise the non-recursive delete will unexpectedly succeed
        filetest.exists must beTrue.await(1, 10.seconds)
      } and {
        // trying to delete non-empty bucket with non recursive deletes (should not work)
        (for {
          _ <- bucket.delete().failed
        } yield true) must beTrue.await(1, 5.seconds)
      } and {
        // the bucket should not be deleted by non-recursive deletes
        bucket must existsIn(storage)
      } and {
        // delete non-empty bucket with recursive delete (should work)
        bucket.delete.recursive() must be_==({}).await(1, 5.seconds)
      } and {
        // check that the bucket is effectively deleted
        bucket must notExistsIn(storage)
      }
    }

    "Get partial content of a file" in assertAllStagesStopped {
      (storage.bucket(bucketName).obj("testfile.txt").
        get(range = Some(ByteRange(4, 9))) runWith consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in assertAllStagesStopped {
      storage.bucket(bucketName).obj("test-folder/DoesNotExist.txt").
        get() runWith consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
    }

    "Write and delete file" in assertAllStagesStopped {
      val file = storage.bucket(bucketName).obj("removable.txt")
      val put = file.put[Array[Byte]]
      val body = List.fill(1000)("qwerty").mkString(" ").getBytes

      {
        file.exists.aka("exists #1") must beFalse.await(1, 5.seconds)
      } and {
        { repeat(5) { body } runWith put }.map(_ => {}) must be_==({}).await(1, 10.seconds)
      } and {
        file.exists.aka("exists #2") must beTrue.await(1, 10.seconds)
      } and {
        file.delete() must be_==({}).await(1, 10.seconds)
      } and {
        file.exists.aka("exists #3") must beFalse.await(1, 10.seconds)
      } and {
        file.delete().failed.map(_ => {}) must be_==({}).await(1, 10.seconds)
      }
    }

    "Write and copy file" in assertAllStagesStopped {
      val file1 = storage.bucket(bucketName).obj("testfile1.txt")
      val file2 = storage.bucket(bucketName).obj("testfile2.txt")

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
          _ <- Future.sequence(Seq(file1.delete(), file2.delete()))
          a <- file1.exists
          b <- file2.exists
        } yield a -> b) must beEqualTo(false -> false).await(1, 10.seconds)
      }
    }

    "Write and move file" >> {
      def moveSpec[R](target: => Future[ObjectRef], preventOverwrite: Boolean = true)(onMove: (ObjectRef, ObjectRef, Future[Unit]) => MatchResult[Future[R]]) = {
        val file3 = storage.bucket(bucketName).obj("testfile3.txt")

        file3.exists.aka("exists #3") must beFalse.await(1, 5.seconds) and (
          target must beLike[ObjectRef] {
            case file4 =>
              val write = file3.put[Array[Byte]]
              val body = List.fill(1000)("qwerty").mkString(" ").getBytes

              { repeat(20) { body } runWith write }.flatMap(_ => file3.exists).
                aka("exists") must beTrue.await(1, 10.seconds) and {
                  onMove(file3, file4, file3.moveTo(file4, preventOverwrite))
                } and {
                  (for {
                    _ <- file3.delete().recoverWith {
                      case _: IllegalArgumentException =>
                        Future.successful({})
                      case err => Future.failed[Unit](err)
                    }
                    _ <- file4.delete()
                    a <- file3.exists
                    b <- file4.exists
                  } yield a -> b) must beEqualTo(false -> false).
                    await(1, 10.seconds)
                }
          }.await(1, 10.seconds))
      }

      @inline def successful(file3: ObjectRef, file4: ObjectRef, res: Future[Unit]) = (for {
        _ <- res
        a <- file3.exists
        b <- file4.exists
      } yield a -> b) must beEqualTo(false -> true).await(1, 10.seconds)

      @inline def failed(file3: ObjectRef, file4: ObjectRef, res: Future[Unit]) = res.recoverWith {
        case _: IllegalStateException => for {
          a <- file3.exists
          b <- file4.exists
        } yield a -> b
      } must beEqualTo(true -> true).await(1, 10.seconds)

      @inline def existingTarget: Future[ObjectRef] = {
        val target = storage.bucket(bucketName).obj("testfile4.txt")
        val write = target.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith write }.map(_ => target)
      }

      "if prevent overwrite when target doesn't exist" in assertAllStagesStopped {
        moveSpec(Future.successful(storage.bucket(bucketName).obj("testfile4.txt")))(successful)
      }

      "if prevent overwrite when target exists" in assertAllStagesStopped {
        moveSpec(existingTarget)(failed)
      }

      "if overwrite when target exists" in assertAllStagesStopped {
        moveSpec(existingTarget, preventOverwrite = false)(successful)
      }
    }

    "Delete on buckets successfully ignore when not existing" in {
      val bucket = storage.bucket(s"benji-test-testignore-${System identityHashCode storage}")

      {
        bucket must notExistsIn(storage)
      } and {
        bucket.create() must beTrue.await(1, 10.seconds)
      } and {
        bucket must existsIn(storage)
      } and {
        bucket.delete.ignoreIfNotExists() must beEqualTo({}).await(1, 10.seconds)
      } and {
        bucket must notExistsIn(storage)
      } and {
        bucket.delete().failed.map(_ => {}) must beEqualTo({}).await(1, 10.seconds)
      } and {
        bucket.delete.ignoreIfNotExists() must beEqualTo({}).await(1, 10.seconds)
      } and {
        bucket.delete().failed.map(_ => {}) must beEqualTo({}).await(1, 10.seconds)
      }
    }

    "Delete on objects successfully ignore when not existing" in {
      val bucket = storage.bucket(bucketName)
      val obj = bucket.obj("testignoreobj")
      val write = obj.put[Array[Byte]]
      val body = List.fill(10)("qwerty").mkString(" ").getBytes
      def upload = { repeat(5) { body } runWith write }.map(_ => {})

      {
        obj must notExistsIn(bucket)
      } and {
        obj.delete.ignoreIfNotExists() must beEqualTo({}).await(1, 10.seconds)
      } and {
        upload must beEqualTo({}).await(1, 10.seconds)
      } and {
        obj must existsIn(bucket)
      } and {
        obj.delete() must beEqualTo({}).await(1, 10.seconds)
      } and {
        obj.delete().failed.map(_ => {}) must beEqualTo({}).await(1, 10.seconds)
      } and {
        obj.delete.ignoreIfNotExists() must beEqualTo({}).await(1, 10.seconds)
      } and {
        obj.delete().failed.map(_ => {}) must beEqualTo({}).await(1, 10.seconds)
      }
    }

    "Get objects with maximum elements" >> {
      lazy val bucket = storage.bucket(bucketName)

      "after preparing bucket" in {
        bucket.objects.collect[List]().
          map(_.size) must beEqualTo(1).await(1, 5.seconds)
      }

      def createFile(name: String) = {
        val file = bucket.obj(name)
        val put = file.put[Array[Byte], Long]
        val upload = put(0L) { (sz, chunk) =>
          Future.successful(sz + chunk.size)
        }
        val body = List.fill(10)("hello world !!!").mkString(" ").getBytes
        repeat(10)(body) runWith upload
      }

      "after creating more objects" in {
        (1 to 16).foldLeft(ok) { (res, i) =>
          val filename = s"max-test-file-$i"

          res and {
            createFile(filename) must beEqualTo(1590).await(1, 5.seconds)
          } and {
            bucket.obj(filename).exists must beTrue.await(1, 5.seconds)
          }
        }
      }

      "using batch size 6" in {
        bucket.objects.collect[List]().map(_.size) must beEqualTo(17).await(1, 5.seconds) and (
          bucket.objects.withBatchSize(6).collect[List]().
          map(_.size) must beEqualTo(17).await(1, 5.seconds))
      }
    }

    "Not create objects if bucket doesn't exist" in {
      val bucket = storage.bucket("unknown_bucket")
      val newObj = bucket.obj("new_object.txt")
      val write = newObj.put[Array[Byte]]
      val body = List.fill(10)("qwerty").mkString(" ").getBytes
      def upload = { repeat(5) { body } runWith write }.map(_ => {})

      {
        bucket must notExistsIn(storage)
      } and {
        upload.failed.map(_ => "Bucket doesn't exist") must beEqualTo("Bucket doesn't exist").await(1, 10.seconds)
      } and {
        bucket must notExistsIn(storage)
      }
    }

    "Versioning feature should be consistant between buckets and objects" in {
      val bucket = storage.bucket(bucketName)
      val obj = bucket.obj("test-benji-versioning-obj")
      bucket.versioning.isDefined must_=== obj.versioning.isDefined
    }
  }
}

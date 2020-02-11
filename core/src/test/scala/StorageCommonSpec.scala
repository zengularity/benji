package tests.benji

import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.BodyWritable

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult

import com.zengularity.benji.{ ByteRange, ObjectStorage, ObjectRef }

import scala.concurrent.Future

trait StorageCommonSpec extends BenjiMatchers with ErrorCommonSpec {
  self: org.specs2.mutable.Specification =>

  import tests.benji.StreamUtils._

  protected lazy val random = new scala.util.Random(
    System.identityHashCode(self) * System.currentTimeMillis())

  // e.g. S3 doesn't guarantee that right after write operation,
  // the result is available (to read operation) on all the cluster
  protected def rwConsistencyRetry: Int = 5

  protected def rwConsistencyDuration: FiniteDuration = 3.seconds

  // import akka.stream.contrib.TestKit.assertAllStagesStopped
  protected def assertAllStagesStopped[T](f: => T): T = f

  def minimalCommonTests(storage: ObjectStorage, defaultBucketName: String)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv,
    writer: BodyWritable[Array[Byte]]) = {

    val bucketName = defaultBucketName

    sequential

    s"Access $bucketName bucket" in assertAllStagesStopped {
      val bucket = storage.bucket(bucketName)

      bucket must notExistsIn(storage, 1, 3.seconds) and {
        bucket must supportCreation(2, 3.seconds)
      } and {
        bucket must existsIn(storage, rwConsistencyRetry, rwConsistencyDuration)
      }
    }

    s"List objects of the empty $bucketName bucket" in assertAllStagesStopped {
      storage.bucket(bucketName).objects.collect[List]().
        map(_.size) must beTypedEqualTo(0).await(2, 5.seconds)
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
        filetest must notExistsIn(bucket, 1, 3.seconds)
      } and { // upload file
        (repeat(20)(body) runWith upload) must beTypedEqualTo(319980L).
          await(2, 10.seconds)
      } and {
        filetest must existsIn(bucket, 3, 3.seconds)
      }
    }

    "Get contents of file" in assertAllStagesStopped {
      storage.bucket(bucketName).obj("testfile.txt").
        get().runWith(consume) aka "response" must beLike[String] {
          case response => response must startWith("hello world !!!")
        }.await(2, 10.seconds)
    }
  }

  def commonTests(
    storageKind: String,
    storage: ObjectStorage,
    defaultBucketName: String)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv,
    writer: BodyWritable[Array[Byte]]) = {

    lazy val defaultBucketRef = storage.bucket(defaultBucketName)

    lazy val random = new scala.util.Random( // Shadow the global random
      defaultBucketName.hashCode * System.currentTimeMillis())

    sequential

    minimalCommonTests(storage, defaultBucketName)
    errorCommonTests(storage)

    "Create & delete buckets" in assertAllStagesStopped {
      val name = s"benji-test-create-${random.nextInt().toString}"
      val bucket = storage.bucket(name)

      {
        bucket must notExistsIn(storage, 1, 3.seconds)
      } and {
        bucket must supportCreation(1, 3.seconds)
      } and {
        bucket must existsIn(storage, rwConsistencyRetry, rwConsistencyDuration)
      } and {
        bucket.delete() must beTypedEqualTo({}).await(1, 3.seconds)
      } and {
        bucket must notExistsIn(
          storage, rwConsistencyRetry, rwConsistencyDuration)
      }
    }

    "Creating & deleting non-empty buckets" in assertAllStagesStopped {
      val name = s"benji-test-nonempty-${random.nextInt().toString}"
      val bucket = storage.bucket(name)
      val filetest = bucket.obj("testfile.txt")

      bucket must notExistsIn(storage, 1, 10.seconds) and {
        // creating bucket
        bucket.create(failsIfExists = true) must beTypedEqualTo({}).
          await(2, 3.seconds)

      } and {
        bucket must existsIn(storage, rwConsistencyRetry, rwConsistencyDuration)
      } and {
        // uploading file to bucket
        val put = filetest.put[Array[Byte], Long]
        val upload = put(0L, metadata = Map("foo" -> "bar")) { (sz, chunk) =>
          Future.successful(sz + chunk.size)
        }
        val body = "hello world".getBytes()

        Source.single(body).runWith(upload) must beTypedEqualTo(
          body.length.toLong).await(2, 5.seconds)

      } and {
        // checking that the upload operation is effective
        // because otherwise the non-recursive delete will unexpectedly succeed
        filetest must existsIn(bucket, 2, 3.seconds)
      } and {
        // checking that metadata are persisted
        filetest.metadata() must havePairs("foo" -> Seq("bar")).
          await(2, 5.seconds)
      } and {
        // Trying to delete non-empty bucket
        // with non recursive deletes (should not work)
        bucket.delete().failed.map(_ => true) must beTrue.await(2, 3.seconds)
      } and {
        // the bucket should not be deleted by non-recursive deletes
        bucket must existsIn(storage, 2, 5.seconds)
      } and {
        // delete non-empty bucket with recursive delete (should work)
        bucket.delete.recursive() must beTypedEqualTo({}).await(2, 3.seconds)
      } and {
        // check that the bucket is effectively deleted
        bucket must notExistsIn(
          storage, rwConsistencyRetry, rwConsistencyDuration).
          setMessage("after delete")
      }
    }

    "Get partial content of a file" in assertAllStagesStopped {
      (defaultBucketRef.obj("testfile.txt").
        get(range = Some(ByteRange(4, 9))) runWith consume).
        aka("partial content") must beTypedEqualTo("o worl").
        await(2, 5.seconds)
    }

    "Write and delete file" in assertAllStagesStopped {
      val file = defaultBucketRef.obj("removable.txt")
      val put = file.put[Array[Byte]]
      val body = List.fill(1000)("qwerty").mkString(" ").getBytes

      {
        file must notExistsIn(defaultBucketRef, 2, 3.seconds).
          setMessage("exists #1")
      } and {
        repeat(5)(body).runWith(put) must beDone.await(2, 5.seconds)
      } and {
        file must existsIn(defaultBucketRef, 2, 5.seconds).
          setMessage("exists #2")
      } and {
        file.delete() must beTypedEqualTo({}).await(2, 5.seconds)
      } and {
        file must notExistsIn(defaultBucketRef, 3, 5.seconds).
          setMessage("exists #3")
      } and {
        file.delete().failed.map(_ => {}) must beTypedEqualTo({}).
          await(2, 5.seconds)
      }
    }

    "Write and copy file" in assertAllStagesStopped {
      // TODO: Remove once NIC storage store URL-encoded x-amz-copy-source
      // See https://github.com/zengularity/benji/pull/23
      val sourceName = {
        if (storageKind == "ceph") "ceph.txt"
        else "Capture d’écran 2018-11-14 à 09.35.49 (1).png"
      }

      val file1 = defaultBucketRef.obj(sourceName)
      val file2 = defaultBucketRef.obj("testfile2.txt")

      file1 must notExistsIn(defaultBucketRef, 1, 3.seconds).
        setMessage("exists #1") and {
          val put = file1.put[Array[Byte]]
          val body = List.fill(1000)("qwerty").mkString(" ").getBytes

          repeat(20)(body).runWith(put) must beDone.await(2, 5.seconds).
            setMessage("put file1")
        } and {
          file1 must existsIn(
            defaultBucketRef, rwConsistencyRetry, rwConsistencyDuration).
            setMessage("exists #2")
        } and {
          file1.copyTo(file2) must beDone.await(2, 5.seconds).
            setMessage("file1 copied to file2")
        } and {
          file2 must existsIn(
            defaultBucketRef, rwConsistencyRetry, rwConsistencyDuration).
            setMessage("exists after copy")
        } and {
          Future.sequence(Seq(file1.delete(), file2.delete())).
            map(_ => {}) must beTypedEqualTo({}).await(2, 5.seconds)
        } and {
          file1 must notExistsIn(defaultBucketRef, 2, 3.seconds).
            setMessage("file1 must no longer exist")
        } and {
          file2 must notExistsIn(defaultBucketRef, 2, 3.seconds).
            setMessage("file2 must no longer exist")
        }
    }

    "Write and move file" >> {
      def moveSpec[R](target: ObjectRef, preventOverwrite: Boolean = true)(onMove: (ObjectRef, ObjectRef, Future[Unit]) => MatchResult[R]) = {
        val src = defaultBucketRef.obj(
          s"testsrc-${random.nextInt().toString}.txt")

        src must notExistsIn(defaultBucketRef, 2, 3.seconds).
          setMessage("exists before") and {
            val write = src.put[Array[Byte]]
            val body = List.fill(1000)("qwerty").mkString(" ").getBytes

            repeat(20)(body).runWith(write) must beDone.await(2, 3.seconds)
          } and {
            onMove(src, target, src.moveTo(target, preventOverwrite))
          } and {
            (for {
              _ <- src.delete.ignoreIfNotExists()
              _ <- target.delete()
            } yield ()) must beDone.await(2, 5.seconds)
          } and {
            src must notExistsIn(
              defaultBucketRef, rwConsistencyRetry, rwConsistencyDuration).
              setMessage("src after delete")
          } and {
            target must notExistsIn(
              defaultBucketRef, rwConsistencyRetry, rwConsistencyDuration).
              setMessage("target after delete")
          }
      }

      val targetObj = defaultBucketRef.obj(
        s"testfile4-${random.nextInt().toString}.txt")

      val successful = { (_: ObjectRef, _: ObjectRef, res: Future[Unit]) =>
        res must beDone.await(2, 3.seconds)
      }

      @inline def failed(
        src: ObjectRef,
        target: ObjectRef,
        res: Future[Unit]) = {
        res.recover {
          case _: IllegalStateException => ()
        } must beDone.await(2, 3.seconds) and {
          src must existsIn(
            defaultBucketRef, rwConsistencyRetry, rwConsistencyDuration)
        } and {
          target must existsIn(
            defaultBucketRef, rwConsistencyRetry, rwConsistencyDuration)
        }
      }

      @inline def existingTarget: Future[ObjectRef] = {
        val target = targetObj
        val write = target.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        repeat(20)(body).runWith(write).map(_ => target)
      }

      "if prevent overwrite when target doesn't exist" in assertAllStagesStopped {
        moveSpec(targetObj)(successful)
      }

      "if prevent overwrite when target exists" in assertAllStagesStopped {
        existingTarget must beLike[ObjectRef] {
          case obj => moveSpec(obj)(failed)
        }.await(3, 5.seconds)
      }

      "if overwrite when target exists" in assertAllStagesStopped {
        existingTarget must beLike[ObjectRef] {
          case obj => moveSpec(obj, preventOverwrite = false)(successful)
        }.await(3, 5.seconds)
      }
    }

    "Delete on buckets successfully ignore when not existing" in {
      val bucket = storage.bucket(
        s"benji-test-testignore-${random.nextInt().toString}")

      {
        bucket must notExistsIn(storage, 1, 10.seconds)
      } and {
        bucket.create(failsIfExists = true) must beTypedEqualTo({}).
          await(2, 5.seconds)
      } and {
        bucket must existsIn(storage, 2, 7.seconds)
      } and {
        bucket.delete.ignoreIfNotExists() must beTypedEqualTo({}).
          await(2, 5.seconds)
      } and {
        bucket must notExistsIn(
          storage, rwConsistencyRetry, rwConsistencyDuration)
      } and {
        bucket.delete().failed.map(_ => {}) must beTypedEqualTo({}).
          await(2, 5.seconds)
      } and {
        bucket.delete.ignoreIfNotExists() must beDone.await(2, 5.seconds)
      } and {
        bucket.delete().failed.map(_ => {}) must beTypedEqualTo({}).
          await(2, 5.seconds)
      }
    }

    "Delete on objects successfully ignore when not existing" in {
      val body = List.fill(10)("qwerty").mkString(" ").getBytes
      val bucket = defaultBucketRef
      val obj = bucket.obj(s"test-ignore-obj-${random.nextInt().toString}")
      val write = obj.put[Array[Byte]]

      def upload = repeat(5)(body).runWith(write)

      {
        obj must notExistsIn(bucket, 2, 3.seconds)
      } and {
        obj.delete.ignoreIfNotExists() must beDone.await(2, 5.seconds)
      } and {
        upload must beDone.await(2, 5.seconds)
      } and {
        obj must existsIn(bucket, 2, 3.seconds)
      } and {
        obj.delete() must beTypedEqualTo({}).await(2, 5.seconds)
      } and {
        obj.delete().failed.map(_ => {}) must beTypedEqualTo({}).
          await(2, 5.seconds)
      } and {
        obj.delete.ignoreIfNotExists() must beDone.await(2, 5.seconds)
      } and {
        obj.delete().failed.map(_ => {}) must beTypedEqualTo({}).
          await(2, 5.seconds)
      }
    }

    "Get objects with maximum elements" >> {
      lazy val bucket = defaultBucketRef

      "after preparing bucket" in {
        bucket.objects.collect[List]().
          map(_.size) must beTypedEqualTo(1).await(2, 5.seconds)
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

      val prefix = "max-test-file"

      "after creating more objects" in {
        (1 to 16).foldLeft(ok) { (res, i) =>
          val filename = s"$prefix-${i.toString}-${random.nextInt().toString}"

          res and {
            createFile(filename) must beTypedEqualTo(1590L).await(2, 5.seconds)
          } and {
            bucket.obj(filename).exists must beTrue.await(2, 5.seconds)
          }
        }
      }

      "using batch size 6" in {
        bucket.objects.collect[List]().
          map(_.size) must beTypedEqualTo(17).await(2, 5.seconds) and {
            bucket.objects.withBatchSize(6).collect[List]().
              map(_.size) must beTypedEqualTo(17).await(2, 5.seconds)
          }
      }

      s"using prefix '$prefix'" in {
        bucket.objects.withPrefix(prefix).collect[Set]().
          map(_.size) must beTypedEqualTo(16).await(2, 5.seconds) and {
            bucket.objects.withPrefix("foo").collect[Seq]().
              map(_.size) must beTypedEqualTo(0).await(2, 5.seconds)
          }
      }
    }

    "Retrieve headers and metadata" in {
      val bucket = defaultBucketRef
      val obj = bucket.obj("testfile.txt")
      val expectedMap = Map("foo" -> Seq("bar"))

      {
        obj.metadata() must beTypedEqualTo(expectedMap).await(2, 5.seconds)
      } and {
        obj.headers().map(_.filter(_._1.contains("foo")).values.toList) must beTypedEqualTo(expectedMap.values.toList).await(2, 5.seconds)
      }
    }

    "Not create objects if bucket doesn't exist" in {
      val bucket = storage.bucket(s"unknownbucket-${random.nextInt().toString}")
      val newObj = bucket.obj("new_object.txt")
      val write = newObj.put[Array[Byte]]
      val body = List.fill(10)("qwerty").mkString(" ").getBytes
      def upload = repeat(5)(body).runWith(write)

      {
        bucket must notExistsIn(storage, 1, 10.seconds)
      } and {
        upload.failed.map(_ => "Bucket doesn't exist") must beEqualTo("Bucket doesn't exist").await(2, 5.seconds)
      } and {
        bucket must notExistsIn(storage, 1, 10.seconds)
      }
    }

    "Return false when checking object existence of a non-existing bucket" in {
      val bucket = storage.bucket(s"unknownbucket-${random.nextInt().toString}")
      val newObj = bucket.obj("new_object.txt")

      bucket must notExistsIn(storage, 2, 3.seconds) and {
        newObj.exists must beFalse.await(2, 5.seconds)
      }
    }

    "Versioning feature should be consistent between buckets and objects" in {
      val bucket = defaultBucketRef
      val obj = bucket.obj(
        s"benji-test-versioning-obj-${random.nextInt().toString}")

      bucket.versioning.isDefined must_=== obj.versioning.isDefined
    }
  }
}

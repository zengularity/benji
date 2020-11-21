package tests.benji

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ BucketVersioning, ObjectRef, ObjectStorage, ObjectVersioning, VersionedObjectRef }
import com.zengularity.benji.tests.TestUtils.{ bucketAlreadyExists, bucketNotEmpty, bucketNotFound, objectNotFound, versionNotFound }

import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.core.Fragment

trait ErrorCommonSpec extends BenjiMatchers {
  self: org.specs2.mutable.Specification =>

  // import akka.stream.contrib.TestKit.assertAllStagesStopped
  protected def assertAllStagesStopped[T](f: => T): T

  protected def random: scala.util.Random

  protected def rwConsistencyRetry: Int

  protected def rwConsistencyDuration: FiniteDuration

  def errorCommonTests(storage: ObjectStorage)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv): Fragment = {

    val nonExistingBucket = storage.bucket(s"benji-test-non-existing-bucket-${random.nextInt().toString}")
    val existingBucket = storage.bucket(s"benji-test-existing-bucket-${random.nextInt().toString}")

    val existingObj = existingBucket.obj("existing")
    val nonExistingObj = existingBucket.obj("non-existing")
    val objOfNonExistingBucket = nonExistingBucket.obj("testobj")

    def put(obj: ObjectRef, content: Array[Byte], repeat: Int = 1): Future[Unit] = {
      import play.api.libs.ws.DefaultBodyWritables._

      Source.fromIterator(() => Iterator.fill(repeat)(content)).
        runWith(obj.put[Array[Byte]]).map(_ => {})
    }

    def get(obj: ObjectRef): Future[Array[Byte]] = {
      obj.get().runFold(ByteString.empty)(_ ++ _).map(_.toArray)
    }

    "Error handling" >> {
      sequential

      "Setup" in assertAllStagesStopped {
        {
          nonExistingBucket must notExistsIn(storage, 0, 3.seconds)
        } and {
          existingBucket.create(failsIfExists = true) must beTypedEqualTo({}).
            await(1, 5.seconds)

        } and {
          existingBucket must existsIn(
            storage, rwConsistencyRetry, rwConsistencyDuration)

        } and {
          put(
            existingObj,
            "hello".getBytes("UTF-8")) must beDone.await(2, 3.seconds)

        } and {
          existingObj must existsIn(
            existingBucket, rwConsistencyRetry, rwConsistencyDuration)

        } and {
          nonExistingObj must notExistsIn(existingBucket, 0, 3.seconds)
        }
      }

      "BucketNotFoundException should be thrown when" >> {
        "Listing a non-existing bucket" in assertAllStagesStopped {
          nonExistingBucket.objects.collect[List]() must throwA(
            bucketNotFound(nonExistingBucket)).await(2, 5.seconds)
        }

        "Deleting a non-existing bucket" in assertAllStagesStopped {
          eventually(2, 3.seconds) {
            nonExistingBucket.delete() must throwA(
              bucketNotFound(nonExistingBucket)).await(1, 3.seconds)
          } and {
            nonExistingBucket must notExistsIn(
              storage, rwConsistencyRetry, rwConsistencyDuration)
          }
        }

        "Uploading an object in a non-existing bucket" >> {
          "a simple 'hello'" in assertAllStagesStopped {
            eventually(2, 3.seconds) {
              put(objOfNonExistingBucket, "hello".getBytes("UTF-8")).
                aka("put") must throwA(
                  bucketNotFound(nonExistingBucket)).await(1, 3.seconds)
            }
          }

          "using chunks" in assertAllStagesStopped {
            val threshold = objOfNonExistingBucket.defaultThreshold.bytes.toInt
            def data = Array.fill(threshold * 2)('a'.toByte)

            put(objOfNonExistingBucket, data, 2) must throwA(
              bucketNotFound(nonExistingBucket)).await(2, 5.seconds)
          }
        }
      }

      "ObjectNotFoundException should be thrown when" >> {
        "Deleting a non-existing object" in assertAllStagesStopped {
          nonExistingObj.delete() must throwA(
            objectNotFound(nonExistingObj)).await(2, 5.seconds)
        }

        "Deleting an object of a non-existing bucket" in assertAllStagesStopped {
          objOfNonExistingBucket.delete() must throwA(
            objectNotFound(objOfNonExistingBucket)).await(2, 5.seconds)
        }

        "Getting the headers of a non-existing object" in assertAllStagesStopped {
          nonExistingObj.headers() must throwA(
            objectNotFound(nonExistingObj)).await(2, 5.seconds)
        }

        "Getting the headers of an object of a non-existing bucket" in assertAllStagesStopped {
          objOfNonExistingBucket.headers() must throwA(
            objectNotFound(objOfNonExistingBucket)).await(2, 5.seconds)
        }

        "Getting the metadata of a non-existing object" in assertAllStagesStopped {
          nonExistingObj.metadata() must throwA(
            objectNotFound(nonExistingObj)).await(2, 5.seconds)
        }

        "Getting the metadata of an object of a non-existing bucket" in assertAllStagesStopped {
          objOfNonExistingBucket.metadata() must throwA(
            objectNotFound(objOfNonExistingBucket)).await(2, 5.seconds)
        }

        "Getting content of a non-existing object" in assertAllStagesStopped {
          get(nonExistingObj) must throwA(
            objectNotFound(nonExistingObj)).await(2, 5.seconds)
        }

        "Getting content of a object inside a non-existing bucket" in assertAllStagesStopped {
          get(objOfNonExistingBucket) must throwA(
            objectNotFound(objOfNonExistingBucket)).await(2, 5.seconds)
        }
      }

      "BucketNotEmptyException should be thrown when" >> {
        "Deleting a non-empty bucket" in assertAllStagesStopped {
          existingBucket.delete() must throwA(
            bucketNotEmpty(existingBucket)).await(2, 5.seconds)
        }
      }

      "BucketAlreadyExistsException should be thrown when" >> {
        "Creating an existing bucket" in assertAllStagesStopped {
          existingBucket.create(failsIfExists = true) must throwA(
            bucketAlreadyExists(existingBucket)).await(2, 5.seconds)
        }
      }
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def versioningErrorCommonTests(
    storage: ObjectStorage,
    defaultBucketName: String, // must exists before
    defaultObjName: String, // must exists inside the `defaultBucketName`
    sampleVersionId: String)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv): Fragment = {

    val nonExistingBucket = storage.bucket(
      s"benji-test-non-existing-bucket-${random.nextInt().toString}")

    val existingBucket = storage.bucket(defaultBucketName)

    def vNonExistingBucket = nonExistingBucket.versioning.getOrElse(
      throw new IllegalArgumentException("versioning not supported"))

    def vExistingBucket = existingBucket.versioning.getOrElse(
      throw new IllegalArgumentException("versioning not supported"))

    val existingObj = existingBucket.obj(defaultObjName)
    val nonExistingObj = existingBucket.obj("non-existing")
    val objOfNonExistingBucket = nonExistingBucket.obj("testobj")

    def vExistingObj = existingObj.versioning.getOrElse(throw new IllegalArgumentException("versioning not supported"))
    def vNonExistingObj = nonExistingObj.versioning.getOrElse(throw new IllegalArgumentException("versioning not supported"))
    def vObjOfNonExistingBucket = objOfNonExistingBucket.versioning.getOrElse(throw new IllegalArgumentException("versioning not supported"))

    def nonExistingVersion = vExistingObj.version(sampleVersionId)
    def versionOfNonExistingObj = vNonExistingObj.version(sampleVersionId)
    def versionOfNonExistingBucket = vObjOfNonExistingBucket.version(sampleVersionId)

    def get(version: VersionedObjectRef): Future[Array[Byte]] = {
      version.get().runFold(ByteString.empty)(_ ++ _).map(_.toArray)
    }

    "Versioning Error handling" >> {
      sequential

      "Setup" in assertAllStagesStopped {
        {
          nonExistingBucket must notExistsIn(storage, 1, 3.seconds)
        } and {
          existingBucket must existsIn(storage, 2, 3.seconds)
        } and {
          existingObj must existsIn(existingBucket, 2, 5.seconds)
        } and {
          nonExistingObj must notExistsIn(existingBucket, 2, 5.seconds)
        } and {
          existingBucket.versioning must beSome[BucketVersioning]
        } and {
          existingObj.versioning must beSome[ObjectVersioning]
        } and {
          nonExistingVersion must notExistsIn(vExistingBucket, 3, 3.seconds)
        }
      }

      "BucketNotFoundException should be thrown when" >> {
        "Versioning Listing a non-existing bucket" in assertAllStagesStopped {
          vNonExistingBucket.versionedObjects.collect[List]() must throwA(
            bucketNotFound(nonExistingBucket)).await(2, 5.seconds)
        }

        "Setting versioning of a non-existing bucket" in assertAllStagesStopped {
          vNonExistingBucket.setVersioning(enabled = true) must throwA(
            bucketNotFound(nonExistingBucket)).await(2, 5.seconds)
        }

        "Checking versioning of a non-existing bucket" in assertAllStagesStopped {
          vNonExistingBucket.isVersioned must throwA(
            bucketNotFound(nonExistingBucket)).await(2, 5.seconds)
        }
      }

      "ObjectNotFoundException should be thrown when" >> {
        "Listing versions of a non-existing object" in assertAllStagesStopped {
          vNonExistingObj.versions.collect[List]() must throwA(
            objectNotFound(nonExistingObj)).await(2, 5.seconds)
        }

        "Listing versions of an object of a non-existing bucket" in assertAllStagesStopped {
          vObjOfNonExistingBucket.versions.collect[List]() must throwA(
            objectNotFound(objOfNonExistingBucket)).await(2, 5.seconds)
        }
      }

      "VersionNotFoundException should be thrown when" >> {
        "Deleting a non-existing version" in assertAllStagesStopped {
          nonExistingVersion.delete() must throwA(
            versionNotFound(nonExistingVersion)).await(2, 5.seconds)
        }

        "Deleting version of a non-existing object" in assertAllStagesStopped {
          versionOfNonExistingObj.delete() must throwA(
            versionNotFound(versionOfNonExistingObj)).await(2, 5.seconds)
        }

        "Deleting version of a non-existing bucket" in assertAllStagesStopped {
          versionOfNonExistingBucket.delete() must throwA(
            versionNotFound(versionOfNonExistingBucket)).await(2, 5.seconds)
        }

        "Getting headers of a non-existing version" in assertAllStagesStopped {
          nonExistingVersion.headers() must throwA(
            versionNotFound(nonExistingVersion)).await(2, 5.seconds)
        }

        "Getting headers of a version within a non-existing object" in assertAllStagesStopped {
          versionOfNonExistingObj.headers() must throwA(
            versionNotFound(versionOfNonExistingObj)).await(2, 5.seconds)
        }

        "Getting metadata of a non-existing version" in assertAllStagesStopped {
          nonExistingVersion.metadata() must throwA(
            versionNotFound(nonExistingVersion)).await(2, 5.seconds)
        }

        "Getting metadata of a version within a non-existing object" in assertAllStagesStopped {
          versionOfNonExistingObj.metadata() must throwA(
            versionNotFound(versionOfNonExistingObj)).await(2, 5.seconds)
        }

        "Getting content of a non-existing version" in assertAllStagesStopped {
          get(nonExistingVersion) must throwA(
            versionNotFound(nonExistingVersion)).await(2, 5.seconds)
        }

        "Getting content of a version within a non-existing object" in assertAllStagesStopped {
          get(versionOfNonExistingObj) must throwA(
            versionNotFound(versionOfNonExistingObj)).await(2, 5.seconds)
        }

        "Getting content of a version within non-existing bucket" in assertAllStagesStopped {
          get(versionOfNonExistingBucket) must throwA(
            versionNotFound(versionOfNonExistingBucket)).await(2, 5.seconds)
        }
      }
    }
  }
}

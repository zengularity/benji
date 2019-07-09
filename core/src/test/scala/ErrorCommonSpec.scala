package tests.benji

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.util.ByteString
import akka.stream.scaladsl.Source
import akka.stream.Materializer

import org.specs2.concurrent.ExecutionEnv

import com.zengularity.benji.{
  ObjectStorage,
  ObjectRef,
  BucketVersioning,
  ObjectVersioning,
  VersionedObjectRef
}

import com.zengularity.benji.tests.TestUtils.{
  bucketNotEmpty,
  bucketNotFound,
  bucketAlreadyExists,
  objectNotFound,
  versionNotFound
}

trait ErrorCommonSpec extends BenjiMatchers {
  self: org.specs2.mutable.Specification =>

  // import akka.stream.contrib.TestKit.assertAllStagesStopped
  protected def assertAllStagesStopped[T](f: => T): T

  protected def random: scala.util.Random

  def errorCommonTests(storage: ObjectStorage)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv) = {

    val nonExistingBucket = storage.bucket(s"benji-test-non-existing-bucket-${random.nextInt().toString}")
    val existingBucket = storage.bucket(s"benji-test-existing-bucket-${random.nextInt().toString}")

    val existingObj = existingBucket.obj("existing")
    val nonExistingObj = existingBucket.obj("non-existing")
    val objOfNonExistingBucket = nonExistingBucket.obj("testobj")

    def put(obj: ObjectRef, content: Array[Byte], repeat: Int = 1): Future[Unit] = {
      import play.api.libs.ws.DefaultBodyWritables._

      Source.fromIterator(() => Iterator.fill(repeat)(content)).runWith(obj.put[Array[Byte]]).map(_ => {})
    }

    def get(obj: ObjectRef): Future[Array[Byte]] = {
      obj.get().runFold(ByteString.empty)(_ ++ _).map(_.toArray)
    }

    "Error handling" >> {
      sequential

      "Setup" in assertAllStagesStopped {
        {
          nonExistingBucket must notExistsIn(storage, 0, 5.seconds)
        } and {
          existingBucket.create(failsIfExists = true) must beTypedEqualTo({}).
            await(1, 5.seconds)

        } and {
          existingBucket must existsIn(storage, 1, 10.seconds)
        } and {
          put(existingObj, "hello".getBytes) must beTypedEqualTo({}).await(1, 10.seconds)
        } and {
          existingObj must existsIn(existingBucket, 1, 10.seconds)
        } and {
          nonExistingObj must notExistsIn(existingBucket, 1, 10.seconds)
        }
      }

      "BucketNotFoundException should be thrown when" >> {
        "Listing a non-existing bucket" in assertAllStagesStopped {
          nonExistingBucket.objects.collect[List]() must throwA(bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }

        "Deleting a non-existing bucket" in assertAllStagesStopped {
          nonExistingBucket.delete() must throwA(bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }

        "Uploading an object in a non-existing bucket" in assertAllStagesStopped {
          put(objOfNonExistingBucket, "hello".getBytes) must throwA(bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }

        "Uploading an object in a non-existing bucket in chunks" in assertAllStagesStopped {
          val threshold = objOfNonExistingBucket.defaultThreshold.bytes.toInt
          def data = Array.fill(threshold * 2)('a'.toByte)

          put(objOfNonExistingBucket, data, 2) must throwA(
            bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }
      }

      "ObjectNotFoundException should be thrown when" >> {
        "Deleting a non-existing object" in assertAllStagesStopped {
          nonExistingObj.delete() must throwA(objectNotFound(nonExistingObj)).await(1, 10.seconds)
        }

        "Deleting an object of a non-existing bucket" in assertAllStagesStopped {
          objOfNonExistingBucket.delete() must throwA(objectNotFound(objOfNonExistingBucket)).await(1, 10.seconds)
        }

        "Getting the headers of a non-existing object" in assertAllStagesStopped {
          nonExistingObj.headers() must throwA(objectNotFound(nonExistingObj)).await(1, 10.seconds)
        }

        "Getting the headers of an object of a non-existing bucket" in assertAllStagesStopped {
          objOfNonExistingBucket.headers() must throwA(objectNotFound(objOfNonExistingBucket)).await(1, 10.seconds)
        }

        "Getting the metadata of a non-existing object" in assertAllStagesStopped {
          nonExistingObj.metadata() must throwA(objectNotFound(nonExistingObj)).await(1, 10.seconds)
        }

        "Getting the metadata of an object of a non-existing bucket" in assertAllStagesStopped {
          objOfNonExistingBucket.metadata() must throwA(objectNotFound(objOfNonExistingBucket)).await(1, 10.seconds)
        }

        "Getting content of a non-existing object" in assertAllStagesStopped {
          get(nonExistingObj) must throwA(objectNotFound(nonExistingObj)).await(1, 10.seconds)
        }

        "Getting content of a object inside a non-existing bucket" in assertAllStagesStopped {
          get(objOfNonExistingBucket) must throwA(objectNotFound(objOfNonExistingBucket)).await(1, 10.seconds)
        }
      }

      "BucketNotEmptyException should be thrown when" >> {
        "Deleting a non-empty bucket" in assertAllStagesStopped {
          existingBucket.delete() must throwA(bucketNotEmpty(existingBucket)).
            await(1, 10.seconds)
        }
      }

      "BucketAlreadyExistsException should be thrown when" >> {
        "Creating an existing bucket" in assertAllStagesStopped {
          existingBucket.create(failsIfExists = true) must throwA(
            bucketAlreadyExists(existingBucket)).await(1, 10.seconds)
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
    ee: ExecutionEnv) = {

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
          nonExistingBucket must notExistsIn(storage, 1, 10.seconds)
        } and {
          existingBucket must existsIn(storage, 0, 5.seconds)
        } and {
          existingObj must existsIn(existingBucket, 1, 10.seconds)
        } and {
          nonExistingObj must notExistsIn(existingBucket, 1, 10.seconds)
        } and {
          existingBucket.versioning must beSome[BucketVersioning]
        } and {
          existingObj.versioning must beSome[ObjectVersioning]
        } and {
          nonExistingVersion must notExistsIn(vExistingBucket, 1, 10.seconds)
        }
      }

      "BucketNotFoundException should be thrown when" >> {
        "Versioning Listing a non-existing bucket" in assertAllStagesStopped {
          vNonExistingBucket.versionedObjects.collect[List]() must throwA(bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }

        "Setting versioning of a non-existing bucket" in assertAllStagesStopped {
          vNonExistingBucket.setVersioning(enabled = true) must throwA(bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }

        "Checking versioning of a non-existing bucket" in assertAllStagesStopped {
          vNonExistingBucket.isVersioned must throwA(bucketNotFound(nonExistingBucket)).await(1, 10.seconds)
        }
      }

      "ObjectNotFoundException should be thrown when" >> {
        "Listing versions of a non-existing object" in assertAllStagesStopped {
          vNonExistingObj.versions.collect[List]() must throwA(objectNotFound(nonExistingObj)).await(1, 10.seconds)
        }

        "Listing versions of an object of a non-existing bucket" in assertAllStagesStopped {
          vObjOfNonExistingBucket.versions.collect[List]() must throwA(objectNotFound(objOfNonExistingBucket)).await(1, 10.seconds)
        }
      }

      "VersionNotFoundException should be thrown when" >> {
        "Deleting a non-existing version" in assertAllStagesStopped {
          nonExistingVersion.delete() must throwA(versionNotFound(nonExistingVersion)).await(1, 10.seconds)
        }

        "Deleting version of a non-existing object" in assertAllStagesStopped {
          versionOfNonExistingObj.delete() must throwA(versionNotFound(versionOfNonExistingObj)).await(1, 10.seconds)
        }

        "Deleting version of a non-existing bucket" in assertAllStagesStopped {
          versionOfNonExistingBucket.delete() must throwA(versionNotFound(versionOfNonExistingBucket)).await(1, 10.seconds)
        }

        "Getting headers of a non-existing version" in assertAllStagesStopped {
          nonExistingVersion.headers() must throwA(versionNotFound(nonExistingVersion)).await(1, 10.seconds)
        }

        "Getting headers of a version within a non-existing object" in assertAllStagesStopped {
          versionOfNonExistingObj.headers() must throwA(versionNotFound(versionOfNonExistingObj)).await(1, 10.seconds)
        }

        "Getting metadata of a non-existing version" in assertAllStagesStopped {
          nonExistingVersion.metadata() must throwA(versionNotFound(nonExistingVersion)).await(1, 10.seconds)
        }

        "Getting metadata of a version within a non-existing object" in assertAllStagesStopped {
          versionOfNonExistingObj.metadata() must throwA(versionNotFound(versionOfNonExistingObj)).await(1, 10.seconds)
        }

        "Getting content of a non-existing version" in assertAllStagesStopped {
          get(nonExistingVersion) must throwA(versionNotFound(nonExistingVersion)).await(1, 10.seconds)
        }

        "Getting content of a version within a non-existing object" in assertAllStagesStopped {
          get(versionOfNonExistingObj) must throwA(versionNotFound(versionOfNonExistingObj)).await(1, 10.seconds)
        }

        "Getting content of a version within non-existing bucket" in assertAllStagesStopped {
          get(versionOfNonExistingBucket) must throwA(versionNotFound(versionOfNonExistingBucket)).await(1, 10.seconds)
        }
      }
    }
  }
}

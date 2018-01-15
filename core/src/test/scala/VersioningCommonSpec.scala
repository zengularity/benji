package tests.benji

import java.time.{ Instant, ZoneOffset }

import scala.concurrent.duration._

import akka.stream.Materializer

import play.api.libs.ws.BodyWritable

import org.specs2.concurrent.ExecutionEnv

import com.zengularity.benji.{ ObjectStorage, BucketVersioning, BucketRef, VersionedObject, ObjectVersioning }

import scala.concurrent.Future

trait VersioningCommonSpec extends BenjiMatchers { self: org.specs2.mutable.Specification =>
  import tests.benji.StreamUtils._

  def commonVersioningTests(storage: ObjectStorage)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv,
    writer: BodyWritable[Array[Byte]]) = {

    val bucketName = s"benji-test-versioning-${System identityHashCode storage}"
    val objectName = "test-obj"
    val bucket = storage.bucket(bucketName)

    sequential

    "Versioning" >> {

      "BucketRef.versioning should be defined" in {
        bucket.versioning must beSome[BucketVersioning]
      }

      "Buckets versioning should be togglable" in {
        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          {
            bucket must notExistsIn(storage)
          } and {
            bucket.create() must beTrue.await(1, 10.seconds)
          } and {
            bucket must existsIn(storage)
          } and {
            vbucket.isVersioned must beFalse.await(1, 10.seconds)
          } and {
            vbucket.setVersioning(enabled = true).map(_ => true) must beTrue.await(1, 10.seconds)
          } and {
            vbucket.isVersioned must beTrue.await(1, 10.seconds)
          } and {
            vbucket.setVersioning(enabled = false).map(_ => true) must beTrue.await(1, 10.seconds)
          } and {
            vbucket.isVersioned must beFalse.await(1, 10.seconds)
          }
        })
      }

      def createObject(bucket: BucketRef, name: String, content: String): Future[Boolean] = {
        val file = bucket.obj(name)
        val put = file.put[Array[Byte], Long]
        val upload = put(0L) { (sz, chunk) =>
          Future.successful(sz + chunk.size)
        }
        val body = content.getBytes
        (repeat(1)(body) runWith upload).map(_ == content.length)
      }

      "Listing versioned buckets should returns all objects versions" in {
        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          var firstVersion: VersionedObject = null
          var secondVersion: VersionedObject = null

          {
            vbucket.setVersioning(enabled = true).map(_ => true) must beTrue.await(1, 10.seconds)
          } and {
            vbucket.objectsVersions.collect[List]() must beEmpty[List[VersionedObject]].await(1, 10.seconds)
          } and {
            createObject(bucket, objectName, "hello") must beTrue.await(1, 10.seconds)
          } and {
            vbucket.objectsVersions.collect[List]().map(l => {
              {
                l.length must_=== 1
              } and {
                firstVersion = l.head
                firstVersion.name must_=== objectName
              } and {
                firstVersion.size.bytes must_=== "hello".length.toLong
              } and {
                val before = Instant.now.minusSeconds(300).getEpochSecond
                val after = Instant.now.plusSeconds(300).getEpochSecond
                firstVersion.versionCreatedAt.toEpochSecond(ZoneOffset.UTC) must beBetween(before, after)
              } and {
                firstVersion.versionId must not(beEmpty)
              }
            }).await(1, 10.seconds)
          } and {
            // creating a second version of same file
            createObject(bucket, objectName, "hello world") must beTrue.await(1, 10.seconds)
          } and {
            vbucket.objectsVersions.collect[List]().map(l => {
              {
                l.length must_=== 2
              } and {
                l must contain(firstVersion)
              } and {
                secondVersion = l.find(_ != firstVersion).get
                secondVersion.name must_=== objectName
              } and {
                secondVersion.size.bytes must_=== "hello world".length.toLong
              } and {
                val before = firstVersion.versionCreatedAt.toEpochSecond(ZoneOffset.UTC)
                val after = Instant.now.plusSeconds(300).getEpochSecond
                secondVersion.versionCreatedAt.toEpochSecond(ZoneOffset.UTC) must beBetween(before, after)
              } and {
                secondVersion.versionId must not(beEmpty)
              } and {
                secondVersion.versionId must_!== firstVersion.versionId
              }
            }).await(1, 10.seconds)
          }
        })
      }

      "Recursive delete works with non-empty versioned bucket" in {
        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          {
            bucket must existsIn(storage)
          } and {
            bucket.delete.recursive() must beEqualTo({}).await(1, 10.seconds)
          } and {
            bucket must notExistsIn(storage)
          } and {
            // checking that after delete there's no content left when recreating
            bucket.create() must beEqualTo(true).await(1, 10.seconds)
          } and {
            vbucket.isVersioned must beFalse.await(1, 10.seconds)
          } and {
            vbucket.objectsVersions.collect[List]() must beEmpty[List[VersionedObject]].await(1, 10.seconds)
          } and {
            bucket.delete.recursive() must beEqualTo({}).await(1, 10.seconds)
          } and {
            bucket must notExistsIn(storage)
          }
        })
      }

      "Specific version reference should allow you to get its content" in {
        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          var versionId: String = null

          {
            bucket.create() must beTrue.await(1, 10.seconds)
          } and {
            vbucket.setVersioning(enabled = true).map(_ => true) must beTrue.await(1, 10.seconds)
          } and {
            createObject(bucket, objectName, "hello") must beTrue.await(1, 10.seconds)
          } and {
            vbucket.objectsVersions.collect[List]().map(l => {
              {
                l.length must_=== 1
              } and {
                l.head.name must_=== objectName
              } and {
                l.head.size.bytes must_=== "hello".length.toLong
              } and {
                val before = Instant.now.minusSeconds(300).getEpochSecond
                val after = Instant.now.plusSeconds(300).getEpochSecond
                l.head.versionCreatedAt.toEpochSecond(ZoneOffset.UTC) must beBetween(before, after)
              } and {
                versionId = l.head.versionId
                versionId must not(beEmpty)
              }
            }).await(1, 10.seconds)
          } and {
            createObject(bucket, objectName, "hello world") must beTrue.await(1, 10.seconds)
          } and {
            bucket.obj(objectName).get() runWith consume must beEqualTo("hello world").await(1, 10.seconds)
          } and {
            vbucket.obj(objectName, versionId).get() runWith consume must beEqualTo("hello").await(1, 10.seconds)
          }
        })
      }

      "Specific version reference should be deletable" in {
        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          var version1: VersionedObject = null
          var version2: VersionedObject = null
          def v1 = vbucket.obj(objectName, version1.versionId)
          def v2 = vbucket.obj(objectName, version2.versionId)
          val obj = bucket.obj(objectName)

          vbucket.objectsVersions.collect[List]().map(l => {
            val sortedList = l.sortBy(_.size.bytes)
            val len = l.length

            if (len == 2) {
              version1 = sortedList.head
              version2 = sortedList.tail.head
            }

            len must_=== 2
          }).await(1, 10.seconds) and {
            v1.get() runWith consume must beEqualTo("hello").await(1, 10.seconds)
          } and {
            v2.get() runWith consume must beEqualTo("hello world").await(1, 10.seconds)
          } and {
            obj.get() runWith consume must beEqualTo("hello world").await(1, 10.seconds)
          } and {
            v1.delete() must beEqualTo({}).await(1, 10.seconds)
          } and {
            v1 must notExistsIn(vbucket)
          } and {
            v2 must existsIn(vbucket)
          } and {
            obj must existsIn(bucket)
          }
        })
      }

      "ObjectVersionRef built from bucket and from object should be equivalent" in {
        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          var versionId: String = null

          {
            vbucket.objectsVersions.collect[List]().map(l => {
              {
                l.length must_=== 1
              } and {
                versionId = l.head.versionId
                versionId must not(beEmpty)
              }
            }).await(1, 10.seconds)
          } and {
            bucket.obj(objectName).versioning must beSome[ObjectVersioning].which(vobj => {
              vobj.version(versionId) must_=== vbucket.obj(objectName, versionId)
            })
          }
        })
      }

      "ObjectVersioning should allow to list versions of a specific object" in {
        // on purpose use character that needs to be url encoded
        val testObjName = "test obj name"

        bucket.versioning must beSome[BucketVersioning].which(vbucket => {
          {
            vbucket.objectsVersions.collect[List]().map(_.length) must beEqualTo(1).await(1, 10.seconds)
          } and {
            createObject(bucket, testObjName, "test v1") must beTrue.await(1, 10.seconds)
          } and {
            createObject(bucket, testObjName, "test v1.1") must beTrue.await(1, 10.seconds)
          } and {
            vbucket.objectsVersions.collect[List]().map(_.length) must beEqualTo(3).await(1, 10.seconds)
          } and {
            bucket.obj(testObjName).versioning must beSome[ObjectVersioning].which(vobj => {
              vobj.versions.collect[List]().map(l => {
                val sortedList = l.sortBy(_.size.bytes)
                def v1 = sortedList.head
                def v2 = sortedList.tail.head

                {
                  sortedList.length must_=== 2
                } and {
                  v1.name must_=== testObjName
                } and {
                  v1.size.bytes must_=== "test v1".length.toLong
                } and {
                  val before = Instant.now.minusSeconds(300).getEpochSecond
                  val after = Instant.now.plusSeconds(300).getEpochSecond
                  v1.versionCreatedAt.toEpochSecond(ZoneOffset.UTC) must beBetween(before, after)
                } and {
                  v1.versionId must not(beEmpty)
                } and {
                  v1.isDeleteMarker must beFalse
                } and {
                  v2.name must_=== testObjName
                } and {
                  v2.size.bytes must_=== "test v1.1".length.toLong
                } and {
                  val before = Instant.now.minusSeconds(300).getEpochSecond
                  val after = Instant.now.plusSeconds(300).getEpochSecond
                  v2.versionCreatedAt.toEpochSecond(ZoneOffset.UTC) must beBetween(before, after)
                } and {
                  v2.versionId must not(beEmpty)
                } and {
                  v2.isDeleteMarker must beFalse
                }
              }).await(1, 10.seconds)
            })
          }
        })
      }
    }
  }
}

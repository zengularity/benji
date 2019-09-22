package tests.benji

import java.time.{ Instant, ZoneOffset }

import scala.concurrent.duration._

import akka.stream.Materializer

import play.api.libs.ws.BodyWritable

import org.specs2.concurrent.ExecutionEnv

import com.zengularity.benji.{
  ObjectStorage,
  BucketVersioning,
  BucketRef,
  VersionedObject,
  ObjectVersioning
}

import scala.concurrent.Future

// TODO: Remove annotation
@SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
trait VersioningCommonSpec extends BenjiMatchers with ErrorCommonSpec { self: org.specs2.mutable.Specification =>
  import tests.benji.StreamUtils._

  protected def random: scala.util.Random

  protected def rwConsistencyRetry: Int

  protected def rwConsistencyDuration: FiniteDuration

  def commonVersioningTests(storage: ObjectStorage, sampleVersionId: String)(
    implicit
    materializer: Materializer,
    ee: ExecutionEnv,
    writer: BodyWritable[Array[Byte]]) = {

    val bucketName = s"benji-test-versioning-${random.nextInt().toString}"
    val objectName = "test-obj"
    val bucket = storage.bucket(bucketName)

    sequential

    s"support versioning for $bucketName bucket" >> {
      "so be defined" in {
        bucket.versioning must beSome[BucketVersioning]
      }

      // to list versions of a specific object
      "so be togglable" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          {
            bucket must notExistsIn(storage, 1, 3.seconds)
          } and {
            bucket.create(failsIfExists = true) must beTypedEqualTo({}).
              setMessage("created").await(2, 5.seconds)
          } and {
            bucket must existsIn(storage, 3, 3.seconds)
          } and {
            vbucket.isVersioned must beFalse.await(3, 3.seconds).
              setMessage("versioned before")

          } and {
            vbucket.setVersioning(enabled = true) must beLike[Unit] {
              case _ => vbucket.isVersioned must beTrue.await(2, 3.seconds).
                setMessage("!versioned after #1")

            }.await(1, 3.seconds).
              eventually(rwConsistencyRetry, rwConsistencyDuration).
              setMessage("versioning enabled")

          } and {
            vbucket.setVersioning(enabled = false) must beLike[Unit] {
              case _ => vbucket.isVersioned must beFalse.await(2, 3.seconds).
                setMessage("!versioned after #2")
            }.await(1, 3.seconds).
              eventually(rwConsistencyRetry, rwConsistencyDuration).
              setMessage("versioning disabled")
          }
        }
      }

      def createObject(bucket: BucketRef, name: String, content: String, metadata: Map[String, String] = Map.empty): Future[Boolean] = {
        val file = bucket.obj(name)
        val put = file.put[Array[Byte], Long]
        val upload = put(0L, metadata = metadata) { (sz, chunk) =>
          Future.successful(sz + chunk.size)
        }
        val body = content.getBytes

        (repeat(1)(body) runWith upload).map(_ == content.length)
      }

      "to return all object versions" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          @SuppressWarnings(
            Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
          var firstVersion: VersionedObject = null

          {
            vbucket.setVersioning(enabled = true).
              map(_ => true) must beTrue.await(1, 3.seconds)

          } and {
            vbucket.versionedObjects.collect[List]() must beEmpty[List[VersionedObject]].await(2, 5.seconds)
          } and {
            createObject(bucket, objectName, "hello") must beTrue.
              await(1, 3.seconds)

          } and {
            vbucket.versionedObjects.
              collect[List]() must beLike[List[VersionedObject]] {
                case firstVer :: Nil => firstVer.name must_=== objectName and {
                  firstVer.size.bytes must_=== "hello".length.toLong
                } and {
                  val before = Instant.now.minusSeconds(300).getEpochSecond
                  val after = Instant.now.plusSeconds(300).getEpochSecond

                  firstVer.versionCreatedAt.toEpochSecond(
                    ZoneOffset.UTC) must beBetween(before, after)

                } and {
                  firstVer.versionId must not(beEmpty)
                } and {
                  // future usage of firstVersion will be when firstVersion is no more the latest
                  firstVersion = firstVer.copy(isLatest = false)
                  ok
                }
              }.await(2, 5.seconds)
          } and {
            // creating a second version of same file
            createObject(bucket, objectName, "hello world") must beTrue.
              await(2, 3.seconds)
          } and {
            vbucket.versionedObjects.
              collect[List]() must beLike[List[VersionedObject]] {
                case l @ (_ :: _ :: Nil) => {
                  l must contain(firstVersion)
                } and {
                  l.find(_ != firstVersion).
                    aka("2nd version") must beSome[VersionedObject].
                    which { ver =>
                      ver.name must_=== objectName and {
                        ver.size.bytes must_=== "hello world".length.toLong
                      } and {
                        val before = ver.versionCreatedAt.
                          toEpochSecond(ZoneOffset.UTC)

                        val after = Instant.now.plusSeconds(300).getEpochSecond

                        ver.versionCreatedAt.toEpochSecond(
                          ZoneOffset.UTC) must beBetween(before, after)

                      } and {
                        ver.versionId must not(beEmpty[String])
                      } and {
                        ver.versionId must_!== firstVersion.versionId
                      }
                    }
                }
              }.await(2, 5.seconds)
          }
        }
      }

      "to keep all object versions when versioning is disabled" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          @SuppressWarnings(Array("org.wartremover.warts.Null", "org.wartremover.warts.Var"))
          var allVersions: Set[VersionedObject] = null

          {
            createObject(bucket, objectName, "hello again !") must beTrue.
              await(2, 3.seconds)
          } and {
            val req = vbucket.versionedObjects.collect[Set]()
            req.foreach(allVersions = _)
            req.map(_.size) must beTypedEqualTo(3).await(2, 5.seconds)
          } and {
            vbucket.setVersioning(enabled = false).
              map(_ => {}) must beTypedEqualTo({}).await(2, 5.seconds)

          } and {
            vbucket.versionedObjects.
              collect[Set]() must beTypedEqualTo(allVersions).
              setMessage(s"""!= ${allVersions.mkString("[", ",", "]")}""").
              await(3, 3.seconds)
          }
        }
      }

      "to delete recursively non-empty versioned bucket" in {
        bucket must existsIn(storage, 2, 3.seconds) and {
          bucket.versioning must beSome[BucketVersioning].which { vbucket =>
            {
              vbucket.setVersioning(enabled = true).
                map(_ => {}) must beTypedEqualTo({}).
                setMessage("versioning disabled before").await(2, 5.seconds)

            } and {
              bucket.delete.recursive() must beTypedEqualTo({}).
                setMessage("deleted #1").await(2, 5.seconds)

            } and {
              bucket must notExistsIn(
                storage, rwConsistencyRetry, rwConsistencyDuration).
                setMessage("!exists #1")

            } and {
              // Checking that after delete there's no content left
              // when recreating
              bucket.create(failsIfExists = true) must beTypedEqualTo({}).
                setMessage("created").await(2, 5.seconds)

            } and {
              vbucket.isVersioned must beFalse.
                await(rwConsistencyRetry, 3.seconds).
                setMessage("versioned")

            } and {
              vbucket.versionedObjects.
                collect[List]() must beEmpty[List[VersionedObject]].
                setMessage("!contain object").await(2, 5.seconds)
            } and {
              bucket.delete.recursive() must beTypedEqualTo({}).
                setMessage("deleted #2").await(2, 5.seconds)

            } and {
              bucket must notExistsIn(
                storage, rwConsistencyRetry, 3.seconds).
                setMessage("!exists #2")
            }
          }
        }
      }

      "to get the content and metadata of a specific version by reference" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          {
            bucket.create(failsIfExists = true) must beTypedEqualTo({}).
              await(2, 5.seconds)
          } and {
            bucket aka "bucket before" must existsIn(storage, 2, 3.seconds)
          } and {
            vbucket.setVersioning(enabled = true).
              map(_ => true) must beTrue.await(2, 5.seconds)

          } and {
            createObject(
              bucket, objectName, "hello", Map("foov1" -> "barv1")) must beTrue.
              await(2, 5.seconds)

          } and {
            vbucket.versionedObjects.
              collect[List]() must beLike[List[VersionedObject]] {
                case ver :: Nil => {
                  ver.name must_=== objectName
                } and {
                  ver.size.bytes must_=== 5L //"hello".length.toLong
                } and {
                  val before = Instant.now.minusSeconds(300).getEpochSecond
                  val after = Instant.now.plusSeconds(300).getEpochSecond

                  ver.versionCreatedAt.toEpochSecond(
                    ZoneOffset.UTC) must beBetween(before, after)

                } and {
                  ver.versionId must not(beEmpty)
                } and {
                  createObject(bucket, objectName, "hello world",
                    Map("foov2" -> "barv2")) must beTrue.await(2, 5.seconds)

                } and {
                  bucket.obj(objectName).get().
                    runWith(consume) must beTypedEqualTo("hello world").
                    await(2, 5.seconds)
                } and {
                  bucket.obj(objectName).metadata() must beTypedEqualTo(
                    Map("foov2" -> Seq("barv2"))).await(2, 5.seconds)

                } and {
                  vbucket.obj(objectName, ver.versionId).get().
                    runWith(consume) must beTypedEqualTo("hello").
                    await(2, 5.seconds)
                } and {
                  vbucket.obj(objectName, ver.versionId).
                    metadata() must beTypedEqualTo(
                      Map("foov1" -> Seq("barv1"))).await(2, 5.seconds)
                }
              }.await(3, 10.seconds)
          }
        }
      }

      "to delete specific version by reference" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          val obj = bucket.obj(objectName)

          vbucket.versionedObjects.collect[List]().map { l =>
            l.sortBy(_.size.bytes) must beLike[List[VersionedObject]] {
              case version1 :: version2 :: Nil =>
                val v1 = vbucket.obj(version1.name, version1.versionId)
                val v2 = vbucket.obj(version2.name, version2.versionId)

                v1.get().runWith(consume) must beTypedEqualTo(
                  "hello").await(2, 5.seconds) and {

                    v2.get().runWith(consume) must beTypedEqualTo(
                      "hello world").await(2, 5.seconds)

                  } and {
                    obj.get().runWith(consume) must beTypedEqualTo(
                      "hello world").await(2, 5.seconds)
                  } and {
                    v1.delete() must beTypedEqualTo({}).await(2, 5.seconds)
                  } and {
                    v1 must notExistsIn(vbucket, 2, 5.seconds)
                  } and {
                    v2 must existsIn(vbucket, 2, 5.seconds)
                  } and {
                    obj must existsIn(bucket, 2, 5.seconds)
                  }
            }
          }.await(2, 10.seconds)
        }
      }

      "to build ObjectVersionRef from bucket or object" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          vbucket.versionedObjects.
            collect[List]() must beLike[List[VersionedObject]] {
              case v :: Nil => v.versionId must not(beEmpty) and {
                bucket.obj(v.name).
                  versioning must beSome[ObjectVersioning].which {
                    _.version(v.versionId) must_=== vbucket.obj(
                      v.name, v.versionId)
                  }
              }
            }.await(3, 5.seconds)
        }
      }

      // on purpose use character that needs to be url encoded
      lazy val testObjName = s"test obj name ${random.nextInt().toString}"

      "to list versions of a specific object" in {
        bucket.versioning must beSome[BucketVersioning].which { vbucket =>
          {
            vbucket.versionedObjects.collect[List]().
              map(_.length) must beTypedEqualTo(1).await(2, 3.seconds)

          } and {
            createObject(bucket, testObjName, "test v1") must beTrue.
              await(2, 3.seconds)

          } and {
            createObject(bucket, testObjName, "test v1.1") must beTrue.
              await(2, 3.seconds)

          } and {
            // this allow to test that we're not just using prefix in listings
            createObject(bucket, testObjName + " other", "test excluded") must beTrue.
              await(2, 3.seconds)

          } and {
            vbucket.versionedObjects.collect[List]().
              map(_.length) must beTypedEqualTo(4).await(2, 3.seconds)

          } and {
            bucket.obj(testObjName).
              versioning must beSome[ObjectVersioning].which { vobj =>
                vobj.versions.collect[List]().map { l =>
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
                    v1.versionCreatedAt.toEpochSecond(
                      ZoneOffset.UTC) must beBetween(before, after)

                  } and {
                    v1.versionId must not(beEmpty)
                  } and {
                    v1.isLatest must beFalse
                  } and {
                    v2.name must_=== testObjName
                  } and {
                    v2.size.bytes must_=== "test v1.1".length.toLong
                  } and {
                    val before = Instant.now.minusSeconds(300).getEpochSecond
                    val after = Instant.now.plusSeconds(300).getEpochSecond

                    v2.versionCreatedAt.toEpochSecond(
                      ZoneOffset.UTC) must beBetween(before, after)

                  } and {
                    v2.versionId must not(beEmpty)
                  } and {
                    v2.isLatest must beTrue
                  }
                }.await(3, 10.seconds)
              }
          }
        }
      }

      "to handle properly object deletion" in {
        val obj = bucket.obj(testObjName)

        obj.versioning must beSome[ObjectVersioning].which { vobj =>
          {
            vobj.versions.collect[List]().map { l =>
              {
                l must haveSize(2)
              } and {
                forall(l) { _.name must beTypedEqualTo(testObjName) }
              } and {
                l must contain(beLike[VersionedObject] {
                  case o: VersionedObject if o.isLatest => ok
                }).exactly(1)
              }
            }.await(2, 5.seconds)
          } and {
            obj must existsIn(bucket, 2, 3.seconds)
          } and {
            obj.delete() must beDone.await(2, 5.seconds).setMessage("delete")
          } and {
            vobj.versions.collect[List]().map { l =>
              {
                l must haveSize(2)
              } and {
                forall(l) { _.name must beTypedEqualTo(testObjName) }
              } and {
                l must contain(beLike[VersionedObject] {
                  case o: VersionedObject if o.isLatest => ok
                }).exactly(0)
              }
            }.await(2, 5.seconds)
          } and {
            obj must notExistsIn(bucket, 3, 5.seconds)
          }
        }
      }
    }

    // Expect bucketName & objName to still exist (so not delete them before)
    versioningErrorCommonTests(storage, bucketName, objectName, sampleVersionId)
  }
}

package tests.benji

import akka.stream.Materializer

import com.zengularity.benji.{
  BucketRef,
  ObjectRef,
  ObjectStorage,
  BucketVersioning,
  VersionedObjectRef
}

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{ Matcher, Matchers }

import scala.concurrent.duration._

trait BenjiMatchers { self: Matchers =>
  def existsIn(storage: ObjectStorage)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] = existsOrNot(storage, expected = true)

  def notExistsIn(storage: ObjectStorage)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] = existsOrNot(storage, expected = false)

  def existsIn(bucket: BucketRef)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[ObjectRef] = existsOrNot(bucket, expected = true)

  def notExistsIn(bucket: BucketRef)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[ObjectRef] = existsOrNot(bucket, expected = false)

  def supportCreation(implicit ee: ExecutionEnv): Matcher[BucketRef] = {
    implicit val ec = ee.executionContext

    beTypedEqualTo({}).await(1, 5.seconds) ^^ { bucket: BucketRef =>
      bucket.create()
    }
  }

  def supportCheckedCreation(implicit ee: ExecutionEnv): Matcher[BucketRef] = {
    implicit val ec = ee.executionContext

    beTypedEqualTo[Unit]({}).await(1, 5.seconds) ^^ { bucket: BucketRef =>
      bucket.create(failsIfExists = true)
    }
  }

  def existsIn(vbucket: BucketVersioning)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[VersionedObjectRef] = existsOrNot(vbucket, expected = true)

  def notExistsIn(vbucket: BucketVersioning)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[VersionedObjectRef] = existsOrNot(vbucket, expected = false)

  // ---

  private def existsOrNot(storage: ObjectStorage, expected: Boolean)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] = {
    implicit val ec = ee.executionContext
    val matcher = beTypedEqualTo(expected)
    val futureMatcher = matcher.await(1, 10.seconds)
    val matchFromBucket = futureMatcher ^^ { (bucket: BucketRef) => bucket.exists }
    val matchFromStorage = futureMatcher ^^ { (bucket: BucketRef) => storage.buckets.collect[List]().map(_.exists(_.name == bucket.name)) }
    matchFromBucket and matchFromStorage
  }

  private def existsOrNot(bucket: BucketRef, expected: Boolean)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[ObjectRef] = {
    implicit val ec = ee.executionContext
    val matcher = beTypedEqualTo(expected)
    val futureMatcher = matcher.await(1, 10.seconds)
    val matchFromObject = futureMatcher ^^ { (obj: ObjectRef) => obj.exists }
    val matchFromBucket = futureMatcher ^^ { (obj: ObjectRef) => bucket.objects.collect[List]().map(_.exists(_.name == obj.name)) }
    matchFromObject and matchFromBucket
  }

  private def existsOrNot(vbucket: BucketVersioning, expected: Boolean)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[VersionedObjectRef] = {
    implicit val ec = ee.executionContext
    val matcher = ===(expected)
    val futureMatcher = matcher.await(1, 10.seconds)
    val matchFromVersion = futureMatcher ^^ { (version: VersionedObjectRef) => version.exists }
    val matchFromBucket = futureMatcher ^^ { (version: VersionedObjectRef) =>
      vbucket.objectsVersions.collect[List]().map(_.exists(v => v.name == version.name && v.versionId == version.versionId))
    }
    matchFromVersion and matchFromBucket
  }
}

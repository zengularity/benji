package tests.benji

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import com.zengularity.benji.{
  Bucket,
  BucketRef,
  ObjectRef,
  ObjectStorage,
  BucketVersioning,
  VersionedObjectRef
}

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{ Matcher, Matchers }

trait BenjiMatchers { self: Matchers =>
  def existsIn(
    storage: ObjectStorage,
    retries: Int, duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] =
    existsOrNot(storage, true, retries, duration)

  def notExistsIn(
    storage: ObjectStorage,
    retries: Int, duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] =
    existsOrNot(storage, false, retries, duration)

  def existsIn(
    bucket: BucketRef,
    retries: Int, duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[ObjectRef] =
    existsOrNot(bucket, true, retries, duration)

  def notExistsIn(
    bucket: BucketRef,
    retries: Int, duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[ObjectRef] =
    existsOrNot(bucket, false, retries, duration)

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

  def existsIn(
    vbucket: BucketVersioning,
    retries: Int, duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[VersionedObjectRef] =
    existsOrNot(vbucket, true, retries, duration)

  def notExistsIn(
    vbucket: BucketVersioning,
    retries: Int, duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[VersionedObjectRef] =
    existsOrNot(vbucket, false, retries, duration)

  // ---

  private def existsOrNot[T](
    exists: T => Future[Boolean],
    contains: T => Future[Boolean],
    expected: Boolean,
    retries: Int,
    duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv): Matcher[T] = {
    val matcher = beTypedEqualTo(expected)
    val futureMatcher = matcher.await(retries, duration)

    (futureMatcher ^^ exists).
      setMessage(s"exists != ${expected.toString}") and {
        (futureMatcher ^^ contains).
          setMessage(s"storage contains != ${expected.toString}")
      }
  }

  private def existsOrNot(
    storage: ObjectStorage,
    expected: Boolean,
    retries: Int,
    duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] = {
    implicit val ec = ee.executionContext

    existsOrNot[BucketRef](
      exists = _.exists,
      contains = { ref: BucketRef =>
        storage.buckets().filter(_.name == ref.name).
          runWith(Sink.headOption[Bucket]).map(_.isDefined)
      },
      expected = expected,
      retries = retries,
      duration = duration)
  }

  private def existsOrNot(
    bucket: BucketRef,
    expected: Boolean,
    retries: Int,
    duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[ObjectRef] = {
    implicit val ec = ee.executionContext

    existsOrNot[ObjectRef](
      exists = _.exists,
      contains = { ref: ObjectRef =>
        bucket.objects().filter(_.name == ref.name).
          runWith(Sink.headOption[Object]).map(_.isDefined)
      },
      expected = expected,
      retries = retries,
      duration = duration)
  }

  private def existsOrNot(
    bucket: BucketVersioning,
    expected: Boolean,
    retries: Int,
    duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[VersionedObjectRef] = {
    implicit val ec = ee.executionContext

    existsOrNot[VersionedObjectRef](
      exists = _.exists,
      contains = { ref: VersionedObjectRef =>
        bucket.versionedObjects().filter { v =>
          v.name == ref.name && v.versionId == ref.versionId
        }.runWith(Sink.headOption[Object]).map(_.isDefined)
      },
      expected = expected,
      retries = retries,
      duration = duration)
  }
}

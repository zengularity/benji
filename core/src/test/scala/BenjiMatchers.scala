package tests.benji

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Sink

import com.zengularity.benji.{ Bucket, BucketRef, BucketVersioning, ObjectRef, ObjectStorage, VersionedObjectRef }

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{ Expectable, Matcher, Matchers }

trait BenjiMatchers { self: Matchers =>
  sealed trait Named[T] {
    def apply(subject: T): String
  }

  implicit object NamedBucket extends Named[BucketRef] {
    @inline def apply(bucket: BucketRef): String =
      s"bucket '${bucket.name}'"
  }

  implicit object NamedObject extends Named[ObjectRef] {
    @inline def apply(obj: ObjectRef): String =
      s"object '${obj.name}'"
  }

  implicit object NamedVersionedObject extends Named[VersionedObjectRef] {
    @inline def apply(obj: VersionedObjectRef): String =
      s"object '${obj.name}'"
  }

  // ---

  lazy val beDone: Matcher[Any] = new Matcher[Any] {
    def apply[S](e: Expectable[S]) = result({
      e.value match {
        case _: akka.NotUsed => true
        case _: akka.Done => true
        case _: Unit => true
        case _ => false
      }
    }, "is done", "must be done", e)
  }

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

  def supportCreation(retries: Int, duration: FiniteDuration)(implicit ee: ExecutionEnv): Matcher[BucketRef] = {
    implicit val ec: ExecutionContext = ee.executionContext

    val tries = retries + 1

    beTypedEqualTo({}).
      awaitFor(duration).
      eventually(tries, duration) ^^ { (bucket: BucketRef) =>
        bucket.create()
      }
  }

  def supportCheckedCreation(implicit ee: ExecutionEnv): Matcher[BucketRef] = {
    implicit val ec: ExecutionContext = ee.executionContext

    beTypedEqualTo[Unit]({}).await(1, 5.seconds) ^^ { (bucket: BucketRef) =>
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

  private def booleanMatcher(
    expected: Boolean,
    ok: String,
    ko: String): Matcher[Boolean] = new Matcher[Boolean] {
    def apply[B <: Boolean](e: Expectable[B]) =
      result(e.value == expected, ok, ko, e)
  }

  @inline private def existsMatcher(n: String) =
    booleanMatcher(true, s"$n exists", s"$n must exist")

  @inline private def doesntExistMatcher(n: String) =
    booleanMatcher(false, s"$n doesn't exist", s"$n must not exist")

  @inline private def containsMatcher(n: String) =
    booleanMatcher(true, s"$n is found", s"$n must be found")

  @inline private def doesntContainMatcher(n: String) =
    booleanMatcher(false, s"$n is not found", s"$n must not be found")

  private def existsOrNot[T](
    exists: T => Future[Boolean],
    contains: T => Future[Boolean],
    expected: Boolean,
    retries: Int,
    duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv, nmd: Named[T]): Matcher[T] = {
    val tries = retries + 1
    val sleep = duration * 2.2D

    val xm = new Matcher[T] {
      def apply[U <: T](e: Expectable[U]) = {
        val n = nmd(e.value)
        val underlying = {
          if (expected) existsMatcher(n)
          else doesntExistMatcher(n)
        }

        val m = underlying.awaitFor(duration).
          eventually(tries, sleep) ^^ exists

        m(e)
      }
    }

    val cm = new Matcher[T] {
      def apply[U <: T](e: Expectable[U]) = {
        val n = nmd(e.value)
        val underlying = {
          if (expected) containsMatcher(n)
          else doesntContainMatcher(n)
        }

        val m = underlying.awaitFor(duration).
          eventually(tries, sleep) ^^ contains

        m(e)
      }
    }

    xm and cm
  }

  private def existsOrNot(
    storage: ObjectStorage,
    expected: Boolean,
    retries: Int,
    duration: FiniteDuration)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] = {
    implicit val ec: ExecutionContext = ee.executionContext

    existsOrNot[BucketRef](
      exists = _.exists,
      contains = { (ref: BucketRef) =>
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
    implicit val ec: ExecutionContext = ee.executionContext

    existsOrNot[ObjectRef](
      exists = _.exists,
      contains = { (ref: ObjectRef) =>
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
    implicit val ec: ExecutionContext = ee.executionContext

    existsOrNot[VersionedObjectRef](
      exists = _.exists,
      contains = { (ref: VersionedObjectRef) =>
        bucket.versionedObjects().filter { v =>
          v.name == ref.name && v.versionId == ref.versionId
        }.runWith(Sink.headOption[Object]).map(_.isDefined)
      },
      expected = expected,
      retries = retries,
      duration = duration)
  }
}

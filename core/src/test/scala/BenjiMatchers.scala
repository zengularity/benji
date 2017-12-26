package tests.benji

import akka.stream.Materializer
import com.zengularity.benji.{ BucketRef, ObjectRef, ObjectStorage }
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{ Matcher, Matchers }

import scala.concurrent.duration._

trait BenjiMatchers { self: Matchers =>
  private def existsOrNot[T <: ObjectStorage[T]](storage: T, expected: Boolean)(
    implicit
    ee: ExecutionEnv,
    transport: storage.Pack#Transport,
    materializer: Materializer): Matcher[BucketRef[T]] = {
    implicit val ec = ee.executionContext
    val matcher = ===(expected)
    val futureMatcher = matcher.await(1, 10.seconds)
    val matchFromBucket = futureMatcher ^^ { (bucket: BucketRef[T]) => bucket.exists }
    val matchFromStorage = futureMatcher ^^ { (bucket: BucketRef[T]) => storage.buckets.collect[List]().map(_.exists(_.name == bucket.name)) }
    matchFromBucket and matchFromStorage
  }

  private def existsOrNot[T <: ObjectStorage[T]](bucket: BucketRef[T], expected: Boolean)(
    implicit
    ee: ExecutionEnv,
    transport: bucket.Transport,
    materializer: Materializer): Matcher[ObjectRef[T]] = {
    implicit val ec = ee.executionContext
    val matcher = ===(expected)
    val futureMatcher = matcher.await(1, 10.seconds)
    val matchFromBucket = futureMatcher ^^ { (obj: ObjectRef[T]) => obj.exists }
    val matchFromStorage = futureMatcher ^^ { (obj: ObjectRef[T]) => bucket.objects.collect[List]().map(_.exists(_.name == obj.name)) }
    matchFromBucket and matchFromStorage
  }

  def existsIn[T <: ObjectStorage[T]](storage: T)(
    implicit
    ee: ExecutionEnv,
    transport: storage.Pack#Transport,
    materializer: Materializer): Matcher[BucketRef[T]] = existsOrNot(storage, expected = true)

  def notExistsIn[T <: ObjectStorage[T]](storage: T)(
    implicit
    ee: ExecutionEnv,
    transport: storage.Pack#Transport,
    materializer: Materializer): Matcher[BucketRef[T]] = existsOrNot(storage, expected = false)

  def existsIn[T <: ObjectStorage[T]](bucket: BucketRef[T])(
    implicit
    ee: ExecutionEnv,
    transport: bucket.Transport,
    materializer: Materializer): Matcher[ObjectRef[T]] = existsOrNot(bucket, expected = true)

  def notExistsIn[T <: ObjectStorage[T]](bucket: BucketRef[T])(
    implicit
    ee: ExecutionEnv,
    transport: bucket.Transport,
    materializer: Materializer): Matcher[ObjectRef[T]] = existsOrNot(bucket, expected = false)
}

package tests.benji

import akka.stream.Materializer
import com.zengularity.benji.{ BucketRef, ObjectRef, ObjectStorage }
import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.{ Matcher, Matchers }

import scala.concurrent.duration._

trait BenjiMatchers { self: Matchers =>
  private def existsOrNot(storage: ObjectStorage, expected: Boolean)(
    implicit
    ee: ExecutionEnv,
    materializer: Materializer): Matcher[BucketRef] = {
    implicit val ec = ee.executionContext
    val matcher = ===(expected)
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
    val matcher = ===(expected)
    val futureMatcher = matcher.await(1, 10.seconds)
    val matchFromBucket = futureMatcher ^^ { (obj: ObjectRef) => obj.exists }
    val matchFromStorage = futureMatcher ^^ { (obj: ObjectRef) => bucket.objects.collect[List]().map(_.exists(_.name == obj.name)) }
    matchFromBucket and matchFromStorage
  }

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
}

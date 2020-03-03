package tests.benji.s3

import scala.concurrent.duration._

import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.DefaultBodyWritables._

import org.specs2.specification.core.Fragment
import org.specs2.concurrent.{ ExecutionEnv => EE }

import com.zengularity.benji.s3.tests.TestUtils

import tests.benji.BenjiMatchers

trait S3Spec extends BenjiMatchers { _: org.specs2.mutable.Specification =>
  import TestUtils.withMatEx

  def s3Suite(
    s3f: => com.zengularity.benji.s3.WSS3,
    bucketName: String,
    objName: String)(implicit m: Materializer): Fragment = {
    lazy val storage = s3f

    "paginate the bucket objects" in withMatEx { implicit ee: EE =>
      lazy val bucket = storage.bucket(bucketName)

      import scala.language.reflectiveCalls
      import akka.stream.scaladsl.Source
      type StructType = {
        def list(nextToken: Option[String])(andThen: String => Source[Object, akka.NotUsed])(implicit m: Materializer): Source[Object, akka.NotUsed]
      }

      val ls = {
        @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
        def unsafe = bucket.objects.withBatchSize(6).
          asInstanceOf[bucket.ListRequest with StructType]

        unsafe
      }

      def count[T] =
        akka.stream.scaladsl.Sink.fold[Int, T](0) { (i, _) => i + 1 }

      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      @volatile var tok1 = Option.empty[String]

      @SuppressWarnings(Array("org.wartremover.warts.Var"))
      @volatile var tok2 = Option.empty[String]

      ls.list(Option.empty[String])({ t =>
        tok1 = Some(t)
        Source.empty[Object]
      }).runWith(count) must beEqualTo(6).await(1, 3.seconds) and {
        tok1 must beSome[String] // token for page #2
      } and {
        ls.list(tok1)({ t =>
          tok2 = Some(t)
          Source.empty[Object]
        }).runWith(count) must beEqualTo(6).await(1, 3.seconds) and {
          tok2 must beSome[String] // token for page #3
        } and {
          ls.list(tok2)(_ => Source.empty[Object]).
            runWith(count) must beEqualTo(5).await(1, 3.seconds)
        }
      }
    }

    s"Metadata of a file $objName" in withMatEx { implicit ee: EE =>
      storage.bucket(bucketName).obj(objName).
        headers() must beLike[Map[String, Seq[String]]] {
          case headers => headers.get("x-amz-meta-foo").
            aka("custom metadata") must beSome(Seq("bar"))
        }.await(1, 5.seconds)
    }

    "Use correct toString format on bucket" in {
      storage.bucket("bucketName").toString must_== "WSS3BucketRef(bucketName)"
    }

    "Use correct toString format on object" in {
      storage.bucket("bucketName").obj("objectName").toString must_== "WSS3ObjectRef(bucketName, objectName)"
    }

    s"write and delete a file with object name containing a slash" in withMatEx { implicit ee: EE =>
      val filename = "accounts/testfile.txt"
      val bucket = storage.bucket(bucketName)
      val file = bucket.obj(filename)

      val put = file.put[Array[Byte]]
      val body = List.fill(100)("hello").mkString(" ").getBytes
      val upload = Source.single(body).runWith(put)

      {
        upload must beDone.await(2, 5.seconds)
      } and {
        file must existsIn(bucket, 10, 5.seconds)
      } and {
        file.delete() must beTypedEqualTo({}).await(1, 5.seconds)
      } and {
        file must notExistsIn(bucket, 10, 5.seconds)
      }
    }
  }
}

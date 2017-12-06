package tests.benji.s3

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.scaladsl.{ Sink, Source }

import play.api.libs.ws.DefaultBodyWritables._

import org.specs2.concurrent.{ ExecutionEnv => EE }

import com.zengularity.benji.{ Bucket, ByteRange, Object }

class S3CephSpec extends org.specs2.mutable.Specification {
  import Sources.repeat
  import TestUtils.{ WS, ceph, consume, withMatEx }

  "S3 Ceph" title

  sequential

  @inline implicit def materializer = TestUtils.materializer

  "Client" should {
    val bucketName = s"benji-test-${System identityHashCode this}"
    val objName = "testfile.txt"

    "Access the system" in withMatEx { implicit ee: EE =>
      val bucket = ceph.bucket(bucketName)

      bucket.create().flatMap(_ => ceph.buckets.collect[List]()).map(
        _.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds) and (
          bucket.create(checkBefore = true) must beFalse.await(1, 10.seconds))
    }

    "Create buckets and files" in withMatEx { implicit ee: EE =>
      val s3 = ceph
      val name = s"benji-test-${System identityHashCode s3}"

      val objects = for {
        _ <- s3.bucket(name).create()
        _ <- Source.single("Hello".getBytes) runWith s3.
          bucket(name).obj(objName).put[Array[Byte]]
        _ <- (s3.buckets() runWith Sink.seq[Bucket])
        o <- (s3.bucket(name).objects() runWith Sink.seq[Object])
      } yield o

      objects must beLike[Seq[Object]] {
        case list => list.find(_.name == objName) must beSome
      }.await(1, 10.seconds)
    }

    "List of all buckets" in withMatEx { implicit ee: EE =>
      ceph.buckets.collect[List]().
        map(_.exists(_.name == bucketName)) must beTrue.await(1, 10.seconds)
    }

    s"Write file in $bucketName bucket using multipart" >> {
      "with the default maximum" in withMatEx { implicit ee: EE =>
        val filetest = ceph.bucket(bucketName).obj(objName)
        def part(b: Byte) = Array.fill[Byte](
          filetest.defaultThreshold.bytes.toInt)(b)

        def body = Source.single(
          "hello world !!!".getBytes("UTF-8")) ++ Source.fromIterator(() =>
            Seq(part(1), part(2), part(3)).iterator)

        type PutReq = filetest.RESTPutRequest[Array[Byte], Unit]

        filetest.put[Array[Byte], Unit].aka("request") must beLike[PutReq] {
          case req =>
            val upload = req({}, metadata = Map("foo" -> "bar"))(
              (_, _) => Future.successful({}))

            (body runWith upload).flatMap(_ => filetest.exists).
              aka("upload") must beTrue.await(2, /*10*/ 45.seconds)
        }
      }

      "with a maximum of 2 parts instead of 3" in withMatEx { implicit ee: EE =>
        val obj = ceph.bucket(bucketName).obj("testfile3.txt")
        @volatile var partCount = 0

        // part = max part (max size up to the default threshold)
        // body = 3 max parts
        val part = Array.fill[Byte](obj.defaultThreshold.bytes.toInt)(1)
        def body = repeat(3)(part)

        def req = obj.put[Array[Byte], Unit].withMaxPart(2)
        val upload = req({}, size = Some(part.size * 3L)) {
          (_, _) => Future.successful { partCount = partCount + 1 }
        }

        (body runWith upload).flatMap(_ => obj.exists).
          aka("upload") must beTrue.await(2, /*10*/ 45.seconds) and {
            partCount must_== 2
          }
      }
    }

    s"Get contents of $bucketName bucket" in withMatEx { implicit ee: EE =>
      ceph.bucket(bucketName).objects.collect[List]().map(
        _.exists(_.name == objName)) must beTrue.await(1, 10.seconds)
    }

    "Create & delete buckets" in withMatEx { implicit ee: EE =>
      val name = s"test2-${System identityHashCode ceph}"
      val bucket = ceph.bucket(name)

      bucket.exists must beFalse.await(1, 10.seconds) and {
        val bucketsWithTestRemovable =
          bucket.create().flatMap(_ => ceph.buckets.collect[List]())

        bucketsWithTestRemovable.map(
          _.exists(_.name == name)) must beTrue.await(1, 10.seconds)

      } and {
        val existsAfterDelete = (for {
          _ <- bucket.exists
          _ <- bucket.delete
          r <- ceph.buckets.collect[List]()
        } yield r.exists(_.name == name))

        existsAfterDelete must beFalse.await(1, 10.seconds)
      } and (bucket.exists must beFalse.await(1, 10.seconds))
    }

    s"Metadata of a file $objName" in withMatEx { implicit ee: EE =>
      ceph.bucket(bucketName).obj(objName).
        headers() must beLike[Map[String, Seq[String]]] {
          case headers => headers.get("x-amz-meta-foo").
            aka("custom metadata") must beSome(Seq("bar"))
        }.await(1, 5.seconds)
    }

    s"Get content of file $objName" in withMatEx { implicit ee: EE =>
      val objRef = ceph.bucket(bucketName).obj(objName)

      objRef.get must beLike[objRef.RESTGetRequest] {
        case req => (req() runWith consume) aka "content" must beLike[String] {
          case resp =>
            resp.isEmpty must beFalse and (
              resp.startsWith("hello world !!!") aka "starts-with" must beTrue)
        }.await(2, /*10*/ 45.seconds)
      }
    }

    s"Get partial content of a file $objName" in withMatEx { implicit ee: EE =>
      (ceph.bucket(bucketName).obj(objName).
        get(range = Some(ByteRange(4, 9))) runWith consume).
        aka("partial content") must beEqualTo("o worl").await(1, 10.seconds)
    }

    "Fail to get contents of a non-existing file" in withMatEx { implicit ee: EE =>
      ceph.bucket(bucketName).obj("benji-test-folder/DoesNotExist.txt").
        get() runWith consume must throwA[IllegalStateException].like({
          case e => e.getMessage must startWith(s"Could not get the contents of the object benji-test-folder/DoesNotExist.txt in the bucket $bucketName. Response: 404")
        }).await(1, 10.seconds)
    }

    "Write and copy file" in withMatEx { implicit ee: EE =>
      val file1 = ceph.bucket(bucketName).obj("testfile1.txt")
      val file2 = ceph.bucket(bucketName).obj("testfile2.txt")

      file1.exists must beFalse.await(1, 10.seconds) and {
        val upload = file1.put[Array[Byte]]
        val body = List.fill(1000)("qwerty").mkString(" ").getBytes

        { repeat(20) { body } runWith upload }.flatMap(_ => file1.exists).
          aka(s"${file1.name} exists") must beTrue.await(1, 10.seconds)
      } and {
        file1.copyTo(file2).flatMap(_ => file2.exists) must beTrue.
          await(1, 10.seconds)
      } and {
        (for {
          _ <- Future.sequence(Seq(file1.delete, file2.delete))
          a <- file1.exists
          b <- file2.exists
        } yield a -> b) must beEqualTo(false -> false).await(1, 10.seconds)
      }
    }
  }
}

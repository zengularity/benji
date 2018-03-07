package tests.benji.s3

import java.net.URI

import scala.util.Failure

import org.specs2.mutable.Specification

import com.zengularity.benji.URIProvider
import com.zengularity.benji.s3.{ S3, WSS3 }
import com.zengularity.benji.s3.tests.TestUtils

class WSS3Spec extends Specification {
  "WSS3" title

  import TestUtils.WS

  "Factory using URI" should {
    "succeed" >> {
      "when given a proper uri as String" in {
        S3("s3:http://accessKey:secretKey@host/?style=path") must beSuccessfulTry
      }

      "when given a proper uri as URI" in {
        val uri = new URI("s3:http://accessKey:secretKey@host/?style=path")

        S3(uri) must beSuccessfulTry
      }

      "when given a proper uri with virtual domain style" in {
        val uri = "s3:http://accessKey:secretKey@domain.host/?style=virtualHost"

        S3(uri) must beSuccessfulTry
      }

      "with request timeout in URI" in {
        val uri = new URI(
          "s3:http://foo:bar@host/?style=path&requestTimeout=12")

        S3(uri) must beSuccessfulTry[WSS3].like {
          case storage => storage.requestTimeout must beSome[Long].which {
            _ aka "request timeout" must_=== 12L
          }
        }
      }
    }

    "fail" >> {
      "when the provider fail" in {
        val exception: Throwable = new Exception("foo")
        implicit val provider = URIProvider[Throwable](Failure[URI])

        S3(exception) must beFailedTry.withThrowable[Exception]("foo")
      }

      "when given a null URI" in {
        @SuppressWarnings(Array("org.wartremover.warts.Null"))
        def test = S3(null: URI)

        test must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "without scheme prefix" in {
        S3("http://accessKey:secretKey@host/?style=path") must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "when given a uri with an incorrect style" in {
        val uri = "s3:http://accessKey:secretKey@domain.host/?style=foo"

        S3(uri) must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "when given a uri without style" in {
        val uri = "s3:http://accessKey:secretKey@domain.host"

        S3(uri) must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "with invalid request timeout in URI" in {
        val uri = new URI(
          "s3:http://foo:bar@host/?style=path&requestTimeout=AB")

        S3(uri) must beFailedTry[WSS3].withThrowable[IllegalArgumentException](
          "Invalid request timeout parameter in URI: AB")
      }
    }
  }
}

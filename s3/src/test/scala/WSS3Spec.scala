package tests.benji.s3

import java.net.URI

import org.specs2.mutable.Specification

import com.zengularity.benji.s3.S3
import com.zengularity.benji.URIProvider

import scala.util.Failure

class WSS3Spec extends Specification {
  "WSS3" title

  "S3.apply using URIs" should {
    "returns Failure when the provider fail" in {
      val exception: Throwable = new Exception("foo")
      implicit val provider = URIProvider[Throwable](Failure[URI])

      S3(exception) must beFailedTry.withThrowable[Exception]("foo")
    }

    "returns Failure when given a null URI" in {
      S3(null: URI) must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "returns Success when given a proper uri as String" in {
      S3("s3:http://accessKey:secretKey@host/?style=path") must beSuccessfulTry
    }

    "returns Success when given a proper uri as URI" in {
      val uri = new URI("s3:http://accessKey:secretKey@host/?style=path")

      S3(uri) must beSuccessfulTry
    }

    "returns Success when given a proper uri with virtual domain style" in {
      val uri = "s3:http://accessKey:secretKey@domain.host/?style=virtualHost"

      S3(uri) must beSuccessfulTry
    }

    "returns Failure without scheme prefix" in {
      S3("http://accessKey:secretKey@host/?style=path") must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "returns Failure when given a uri with an incorrect style" in {
      val uri = "s3:http://accessKey:secretKey@domain.host/?style=foo"

      S3(uri) must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "returns Failure when given a uri without style" in {
      val uri = "s3:http://accessKey:secretKey@domain.host"

      S3(uri) must beFailedTry.withThrowable[IllegalArgumentException]
    }
  }

}

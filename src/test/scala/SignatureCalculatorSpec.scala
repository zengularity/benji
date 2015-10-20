package tests

import scala.concurrent.duration._

import com.ning.http.client.FluentCaseInsensitiveStringsMap

import play.api.test.FakeApplication
import play.api.test.Helpers._

import com.zengularity.s3.SignatureCalculator

// Sanity tests related to calculating the signature for S3 requests.
object SignatureCalculatorSpec extends org.specs2.mutable.Specification {
  "Signature calculator" title

  // Examples taken from:
  // http://s3.amazonaws.com/doc/s3-developer-guide/RESTAuthentication.html

  "Calculated authentication signature" should {
    val signature1 = "5m+HAmc5JsrgyDelh9+a2dNrzN8="
    s"be '$signature1' for request #1" in {
      calculator.calculateFor(
        "GET\n\n\n\n" +
          "x-amz-date:Thu, 17 Nov 2005 18:49:58 GMT\n" +
          "x-amz-magic:abracadabra\n" +
          "/quotes/nelson"
      ).get aka "signature" must_== signature1
    }

    val signature2 = "jZNOcbfWmD/A/f3hSvVzXZjM2HU="
    s"be '$signature2' for request #2" in {
      calculator.calculateFor(
        "PUT\n" +
          "c8fdb181845a4ca6b8fec737b3581d76\n" +
          "text/html\n" +
          "Thu, 17 Nov 2005 18:49:58 GMT\n" +
          "x-amz-magic:abracadabra\n" +
          "x-amz-meta-author:foo@bar.com\n" +
          "/quotes/nelson"
      ).get aka "signature" must_== signature2
    }

    val signature3 = "vjbyPxybdZaNmGa+yT272YEAiv4="
    s"be '$signature3' for request #3" in {
      calculator.calculateFor(
        "GET\n\n\n1141889120\n/quotes/nelson"
      ).get aka "signature" must_== signature3
    }
  }

  "Calculate the canonicalized resource element" should {
    val ipHost = "10.192.8.62"
    val awsHost = "s3.amazonaws.com"

    val ipHostPathStyle = "http://10.192.8.62/johnsmith/photos/puppy.jpg"
    val ipHostVirtualStyle = "http://johnsmith.10.192.8.62/photos/puppy.jpg"

    val awsHostPathStyle = "https://s3.amazonaws.com/johnsmith/photos/puppy.jpg"
    val awsHostVirtualStyle =
      "https://johnsmith.s3.amazonaws.com/photos/puppy.jpg"

    val path = "/johnsmith/photos/puppy.jpg"

    def canonicalizedResourceForTest(url: String, host: String, path: String) =
      s"Calculate $url with $host" in {
        calculator.canonicalizedResourceFor(url, host) must_== path
      }

    canonicalizedResourceForTest(ipHostPathStyle, ipHost, path)

    canonicalizedResourceForTest(ipHostVirtualStyle, ipHost, path)

    canonicalizedResourceForTest(awsHostPathStyle, awsHost, path)

    canonicalizedResourceForTest(awsHostVirtualStyle, awsHost, path)

    canonicalizedResourceForTest(
      "http://com.domain.backet.10.192.8.62/photos/puppy.jpg",
      ipHost, "/com.domain.backet/photos/puppy.jpg"
    )

    canonicalizedResourceForTest(
      "http://10.192.8.62/com.domain.backet/photos/puppy.jpg",
      ipHost, "/com.domain.backet/photos/puppy.jpg"
    )

    "required to list objects within a bucket with /" in {
      calculator.canonicalizedResourceFor(
        "https://bucket-name.s3.amazonaws.com/",
        host = "s3.amazonaws.com"
      ) must_== "/bucket-name/"
    }

    "required to list objects within a bucket" in {
      calculator.canonicalizedResourceFor(
        "https://bucket-name.s3.amazonaws.com",
        host = "s3.amazonaws.com"
      ) must_== "/bucket-name/"
    }

    "required to do multi-part object uploads" in {
      calculator.canonicalizedResourceFor(
        "https://bucket-name.s3.amazonaws.com/object?uploads",
        host = "s3.amazonaws.com"
      ) must_== "/bucket-name/object?uploads"
    }

  }

  "Calculate the canonicalized AMZ headers element" should {
    "SignatureCalculator canonicalizedAmzHeadersFor" in {
      calculator.canonicalizedAmzHeadersFor(Map(
        "x-amz-acl" -> Seq("public-read"),
        "X-Amz-Meta-ReviewedBy" -> Seq(
          "joe@johnsmith.net", "jane@johnsmith.net"
        ),
        "X-Amz-Meta-FileChecksum" -> Seq("0x02661779"),
        "X-Amz-Meta-ChecksumAlgorithm" -> Seq("crc32")
      )) must_== "x-amz-acl:public-read\n" +
        "x-amz-meta-checksumalgorithm:crc32\n" +
        "x-amz-meta-filechecksum:0x02661779\n" +
        "x-amz-meta-reviewedby:joe@johnsmith.net,jane@johnsmith.net\n"
    }

    "SignatureCalculator canonicalizedAmzHeadersFor with FluentCaseInsensitiveStringsMap" in {
      val headers = new FluentCaseInsensitiveStringsMap()

      headers.add("x-amz-acl", "public-read")
      headers.add(
        "X-Amz-Meta-ReviewedBy",
        "joe@johnsmith.net", "jane@johnsmith.net"
      )
      headers.add("X-Amz-Meta-FileChecksum", "0x02661779")
      headers.add("X-Amz-Meta-ChecksumAlgorithm", "crc32")

      calculator.canonicalizedAmzHeadersFor(headers).
        aka("canonicalized") must_== "x-amz-acl:public-read\n" +
        "x-amz-meta-checksumalgorithm:crc32\n" +
        "x-amz-meta-filechecksum:0x02661779\n" +
        "x-amz-meta-reviewedby:joe@johnsmith.net,jane@johnsmith.net\n"
    }
  }

  // ---

  lazy val calculator = new SignatureCalculator(
    accessKey = "44CF9590006BF252F707",
    secretKey = "OtxrzxIsfpFjA7SwPzILwy8Bw21TLhquhboDYROV",
    "s3.amazonaws.com"
  )
}

package com.zengularity.benji.s3 // as testing internals

import org.apache.commons.codec.binary.Hex

import play.shaded.ahc.org.asynchttpclient.RequestBuilder
//import play.shaded.ahc.io.netty.handler.codec.http.DefaultHttpHeaders

// Sanity tests related to calculating the signature for S3 requests.
class SignatureCalculatorV4Spec extends org.specs2.mutable.Specification {
  "Signature calculator (V4)" title

  "Canonical request" should {
    // Examples from https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html

    val req1 = {
      @SuppressWarnings(Array("org.wartremover.warts.Null"))
      def build() = new RequestBuilder().
        setUrl("http://my-bucket.s3.amazonaws.com/documents%20and%20settings/my-object//example").
        addQueryParam("my-param étoile", "a b").
        addQueryParam("np", null).
        addQueryParam("Action", "ListUsers").
        addQueryParam("Version", "2010-05-08").
        addHeader("Host", "my-bucket.s3.amazonaws.com").
        addHeader(
          "Content-Type",
          "application/x-www-form-urlencoded; charset=utf-8").addHeader("My-header1", "a   b   c   ").
          addHeader("X-Amz-Date", "20150830T123600Z").
          addHeader("My-header2", "\"a   b   c\"   ").
          build()

      build()
    }

    "compute canonical non-empty URI" in {
      calculator.canonicalUri(req1) must beTypedEqualTo(
        "/documents%20and%20settings/my-object//example")
    }

    "encode path" in {
      val encodedName = WSRequestBuilder.appendName(
        new StringBuilder(), "foo !#$%&'()*+,/:;=?@[] étoile").result()

      val req = new RequestBuilder().
        setUrl(s"http://my-bucket.s3.amazonaws.com/${encodedName}").build()

      calculator.canonicalUri(req) must_=== "/foo%20%21%23%24%26%26%27%28%29%2A%2B%2C/%3A%3B%3D%3F%40%5B%5D%20%C3%A9toile"
    }

    "compute canonical empty URI" in {
      val req2 = new RequestBuilder().
        setUrl("http://iam.amazonaws.com").build()

      calculator.canonicalUri(req2) must_=== "/"
    }

    "compute canonical query string" in {
      calculator.canonicalQueryString(req1) must beTypedEqualTo(
        "Action=ListUsers&Version=2010-05-08&my-param%20%C3%A9toile=a%20b&np=")
    }

    "compute canonical headers" >> {
      "for request #1" in {
        calculator.canonicalHeaders(req1, "20150830T123600Z") must_=== ("content-type:application/x-www-form-urlencoded; charset=utf-8\nhost:my-bucket.s3.amazonaws.com\nmy-header1:a b c\nmy-header2:\"a b c\"\nx-amz-date:20150830T123600Z\n" -> "content-type;host;my-header1;my-header2;x-amz-date")
      }

      "with request style header" in {
        val req = new RequestBuilder().
          setUrl("http://my-bucket.s3.amazonaws.com").
          addHeader("Host", "my-bucket.s3.amazonaws.com").
          addHeader("Content-Type", "text/plain; charset=UTF-8").
          addHeader("X-Request-Style", "virtualhost").
          addHeader("x-amz-date", "20180329T203920Z").
          build()

        calculator.canonicalHeaders(req, "20180329T203920Z") must_=== (
          """content-type:text/plain; charset=UTF-8
host:my-bucket.s3.amazonaws.com
x-amz-date:20180329T203920Z
x-request-style:virtualhost
""" -> "content-type;host;x-amz-date;x-request-style")

      }
    }

    "be computed" in {
      val req3 = new RequestBuilder().
        setUrl("http://iam.amazonaws.com").
        addQueryParam("Action", "ListUsers").
        addQueryParam("Version", "2010-05-08").
        addHeader("Host", "iam.amazonaws.com").
        addHeader(
          "Content-Type",
          "application/x-www-form-urlencoded; charset=utf-8").
          addHeader("X-Amz-Date", "20150830T123600Z").
          build()

      calculator.canonicalRequest(
        req3, "20150830T123600Z", "UNSIGNED-PAYLOAD") must beTypedEqualTo(
        canonicalRequest1 -> "content-type;host;x-amz-date")
    }
  }

  "String-to-sign" should {
    // See https://docs.aws.amazon.com/general/latest/gr/sigv4-create-string-to-sign.html

    "compute credential scope" in {
      calculator.credentialScope(
        "20150830T123600Z") must_=== credentialScope
    }

    "be computed" in {
      calculator.stringToSign(
        canonicalRequest1, "20150830T123600Z",
        credentialScope) must_=== """AWS4-HMAC-SHA256
20150830T123600Z
20150830/us-east-1/iam/aws4_request
2714b15fec5795e21b0fa0c48f6944f639224b42fd8e71d16f57ed58265f9c7d"""
    }
  }

  "Signature" should {
    // See https://docs.aws.amazon.com/general/latest/gr/sigv4-calculate-signature.html

    "derive signing key" in {
      val mac = javax.crypto.Mac.getInstance("HmacSHA256")

      Hex.encodeHexString(calculator.deriveSigningKey("20150830T123600Z", mac)) must_=== "c4afb1cc5771d871763a393e44b703571b55cc28424d1a5e86da6ed3c154a4b9"
    }

    "be computed" in {
      calculator.calculateSignature(
        "20150830T123600Z", stringToSignX) must_=== signatureX
    }
  }

  "Authorization header" should {
    "be prepared" in {
      calculator.authorizationHeader(
        credentialScope,
        "content-type;host;x-amz-date",
        signatureX) must_=== s"AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/${credentialScope}, SignedHeaders=content-type;host;x-amz-date, Signature=${signatureX}"
    }
  }

  // ---

  private lazy val canonicalRequest1 = s"""GET
/
Action=ListUsers&Version=2010-05-08
content-type:application/x-www-form-urlencoded; charset=utf-8
host:iam.amazonaws.com
x-amz-date:20150830T123600Z

content-type;host;x-amz-date
UNSIGNED-PAYLOAD"""

  private val credentialScope = "20150830/us-east-1/iam/aws4_request"

  // doesn't correspond to `canonicalRequest1`
  private val stringToSignX = """AWS4-HMAC-SHA256
20150830T123600Z
20150830/us-east-1/iam/aws4_request
f536975d06c0309214f805bb90ccff089219ecd68b2577efef23edd43b7e1a59"""

  private val signatureX = "5d672d79c15b13162d9279b0855cfba6789a8edb4c82c400e06b5924a6f2b5d7" // corresponds to stringToSignX

  private lazy val calculator = new SignatureCalculatorV4(
    accessKey = "AKIDEXAMPLE",
    secretKey = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
    awsRegion = "us-east-1",
    awsService = "iam")
}

package com.zengularity.benji.s3 // as testing internals

import play.shaded.ahc.org.asynchttpclient.RequestBuilder
import play.shaded.ahc.io.netty.handler.codec.http.DefaultHttpHeaders

// Sanity tests related to calculating the signature for S3 requests.
class SignatureCalculatorSpec extends org.specs2.mutable.Specification {
  "Signature calculator" title

  // Examples taken from:
  // http://s3.amazonaws.com/doc/s3-developer-guide/RESTAuthentication.html

  "Canolicalized signature url" should {
    "keep sub-resource parameter (e.g. ?acl)" in {
      @SuppressWarnings(Array("org.wartremover.warts.Null"))
      val req = new RequestBuilder("http://localhost/").
        addQueryParam("acl", null).build()

      calculator.signatureUrl(req) must_== "http://localhost?acl"
    }

    "filter query parameters (e.g. ?max-keys)" in {
      val req = new RequestBuilder("http://localhost/").
        addQueryParam("max-keys", "50").build()

      calculator.signatureUrl(req) must_== "http://localhost"
    }
  }

  "Canonicalized resource element" should {
    val ipHost = "10.192.8.62"
    val awsHost = "s3.amazonaws.com"
    val path = "/johnsmith/photos/puppy.jpg"

    def resForTest(style: RequestStyle, url: String, host: String, path: String) = s"be '$path' for $url with $host" in {
      calculator.canonicalizeResource(style, url, host) must_== path
    }
    def vhResTest(url: String, host: String, path: String) =
      resForTest(VirtualHostRequest, url, host, path)

    def pathResTest(url: String, host: String, path: String) =
      resForTest(PathRequest, url, host, path)

    pathResTest("http://10.192.8.62/johnsmith/photos/puppy.jpg", ipHost, path)
    vhResTest("http://johnsmith.10.192.8.62/photos/puppy.jpg", ipHost, path)

    pathResTest(
      "https://s3.amazonaws.com/johnsmith/photos/puppy.jpg", awsHost, path)

    vhResTest(
      "https://johnsmith.s3.amazonaws.com/photos/puppy.jpg", awsHost, path)

    vhResTest(
      "http://com.domain.bucket.10.192.8.62/photos/puppy.jpg",
      ipHost, "/com.domain.bucket/photos/puppy.jpg")

    pathResTest(
      "http://10.192.8.62/com.domain.bucket/photos/puppy.jpg",
      ipHost, "/com.domain.bucket/photos/puppy.jpg")

    vhResTest("http://johnsmith.s3.amazonaws.com/?prefix=photos&max-keys=50&marker=puppy", awsHost, "/johnsmith/?prefix=photos&max-keys=50&marker=puppy")

    vhResTest(
      "http://johnsmith.s3.amazonaws.com/?acl",
      awsHost, "/johnsmith/?acl")

    "required to list objects within a bucket with /" in {
      calculator.canonicalizeResource(
        VirtualHostRequest,
        "https://bucket-name.s3.amazonaws.com/",
        host = "s3.amazonaws.com") must_== "/bucket-name/"
    }

    "required to list objects within a bucket" in {
      calculator.canonicalizeResource(
        VirtualHostRequest,
        "https://bucket-name.s3.amazonaws.com",
        host = "s3.amazonaws.com") must_== "/bucket-name/"
    }

    "required to do multi-part object uploads" in {
      calculator.canonicalizeResource(
        VirtualHostRequest,
        "https://bucket-name.s3.amazonaws.com/object?uploads",
        host = "s3.amazonaws.com") must_== "/bucket-name/object?uploads"
    }
  }

  "Calculate the canonicalized AMZ headers element" should {
    "SignatureCalculator canonicalizeHeaders" in {
      val headers = new DefaultHttpHeaders()

      headers.add("x-amz-acl", "public-read")
      headers.add("X-Amz-Meta-ReviewedBy", "joe@johnsmith.net")
      headers.add("X-Amz-Meta-ReviewedBy", "jane@johnsmith.net")
      headers.add("X-Amz-Meta-FileChecksum", "0x02661779")
      headers.add("X-Amz-Meta-ChecksumAlgorithm", "crc32")

      calculator.canonicalizeHeaders(headers) must_== (
        "x-amz-acl:public-read\n" +
        "x-amz-meta-checksumalgorithm:crc32\n" +
        "x-amz-meta-filechecksum:0x02661779\n" +
        "x-amz-meta-reviewedby:joe@johnsmith.net,jane@johnsmith.net\n")

    }

    "SignatureCalculator canonicalizeHeaders with header map" in {
      val headers = new DefaultHttpHeaders()

      headers.add("x-amz-acl", "public-read")
      headers.add("X-Amz-Meta-ReviewedBy", "joe@johnsmith.net")
      headers.add("X-Amz-Meta-ReviewedBy", "jane@johnsmith.net")
      headers.add("X-Amz-Meta-FileChecksum", "0x02661779")
      headers.add("X-Amz-Meta-ChecksumAlgorithm", "crc32")

      calculator.canonicalizeHeaders(headers).
        aka("canonicalized") must_== "x-amz-acl:public-read\n" +
        "x-amz-meta-checksumalgorithm:crc32\n" +
        "x-amz-meta-filechecksum:0x02661779\n" +
        "x-amz-meta-reviewedby:joe@johnsmith.net,jane@johnsmith.net\n"
    }
  }

  "String-to-sign" should {
    val serverHost = "s3.amazonaws.com"
    val host = s"johnsmith.$serverHost"

    import calculator.stringToSign

    "be computed for http://johnsmith.s3.amazonaws.com/photos/puppy.jpg" in {
      val date = "Tue, 27 Mar 2007 19:36:42 +0000"
      val headers = headerMap("Host" -> host, "Date" -> date)

      val expected = "GET\n\n\nTue, 27 Mar 2007 19:36:42 +0000\n/johnsmith/photos/puppy.jpg"

      stringToSign("GET", VirtualHostRequest, None, None,
        date, headers, serverHost, s"http://$host/photos/puppy.jpg").
        aka("string-to-sign") must_== expected
    }

    "be computed for PUT" >> {
      "to file '/photos/puppy.jpg'" in {
        val date = "Tue, 27 Mar 2007 21:15:45 +0000"
        val contentType = "image/jpeg"
        val headers = headerMap(
          "Content-Type" -> contentType,
          "Content-Length" -> "94328",
          "Host" -> host,
          "Date" -> date)

        val expected = "PUT\n\nimage/jpeg\nTue, 27 Mar 2007 21:15:45 +0000\n/johnsmith/photos/puppy.jpg"

        stringToSign("PUT", VirtualHostRequest,
          None, Some(contentType), date, headers, serverHost,
          s"http://$host/photos/puppy.jpg") must_== expected
      }

      "to bucket in virtual host style" in {
        val date = "Sun, 24 Jan 2016 17:27:45 +0000"
        val contentType = "text/plain; charset=utf-8"
        val headers = headerMap(
          "Content-Type" -> contentType,
          "Host" -> s"bucket-1005827192.$serverHost",
          "Date" -> date)

        val expected = "PUT\n\ntext/plain; charset=utf-8\nSun, 24 Jan 2016 17:27:45 +0000\n/bucket-1005827192/"

        stringToSign("PUT", VirtualHostRequest, None, Some(contentType),
          date, headers, serverHost, s"http://bucket-1005827192.$serverHost").
          aka("string-to-sign") must_== expected
      }
    }

    "be computed for '/?prefix=photos&max-keys=50&marker=puppy'" in {
      val date = "Tue, 27 Mar 2007 19:42:41 +0000"
      val headers = headerMap(
        "User-Agent" -> "Mozilla/5.0",
        "Host" -> host,
        "Date" -> date)

      val expected = "GET\n\n\nTue, 27 Mar 2007 19:42:41 +0000\n/johnsmith/"

      stringToSign("GET", VirtualHostRequest, None, None, date, headers,
        serverHost, s"https://$host/").
        aka("string-to-sign") must_== expected
    }

    "be computed for '/?acl'" in {
      val date = "Tue, 27 Mar 2007 19:44:46 +0000"
      val headers = headerMap("Host" -> host, "Date" -> date)

      val expected = "GET\n\n\nTue, 27 Mar 2007 19:44:46 +0000\n/johnsmith/?acl"

      stringToSign("GET", VirtualHostRequest, None, None, date, headers,
        serverHost, s"https://$host/?acl") must_== expected
    }

    "be computed for 'DELETE /johnsmith/photos/puppy.jpg'" in {
      val date = "Tue, 27 Mar 2007 21:20:26 +0000"
      val headers = headerMap(
        "User-Agent" -> "dotnet",
        "Host" -> serverHost,
        "Date" -> "Tue, 27 Mar 2007 21:20:27 +0000")

      val expected = "DELETE\n\n\nTue, 27 Mar 2007 21:20:26 +0000\n/johnsmith/photos/puppy.jpg"

      stringToSign("DELETE", PathRequest, None, None, date, headers, serverHost,
        s"https://$serverHost/johnsmith/photos/puppy.jpg") must_== expected

    }

    "be computed for 'DELETE /photos/puppy.jpg'" in {
      val date = "Tue, 27 Mar 2007 21:20:26 +0000"
      val headers = headerMap(
        "User-Agent" -> "dotnet",
        "Host" -> host,
        "Date" -> "Tue, 27 Mar 2007 21:20:27 +0000")

      val expected = "DELETE\n\n\nTue, 27 Mar 2007 21:20:26 +0000\n/johnsmith/photos/puppy.jpg"

      stringToSign("DELETE", VirtualHostRequest, None, None, date, headers,
        serverHost, s"https://$host/photos/puppy.jpg") must_== expected

    }

    /* WEIRD canonicalizedResource !!
     // TODO: Review
    "be computed for 'PUT /db-backup.dat.gz'" in {
      val date = "Tue, 27 Mar 2007 21:06:08 +0000"
      val contentType = "application/x-download"
      val contentMd5 = "4gJE4saaMU4BqNR0kLY+lw=="
      val headers = headerMap(
        "User-Agent" -> "curl/7.15.5",
        "Host" -> "static.johnsmith.net:8080",
        "Date" -> date,
        "x-amz-acl" -> "public-read",
        "content-type" -> contentType,
        "Content-MD5" -> contentMd5,
        "X-Amz-Meta-ReviewedBy" -> "joe@johnsmith.net",
        "X-Amz-Meta-ReviewedBy" -> "jane@johnsmith.net",
        "X-Amz-Meta-FileChecksum" -> "0x02661779",
        "X-Amz-Meta-ChecksumAlgorithm" -> "crc32",
        "Content-Disposition" -> "attachment; filename=database.dat",
        "Content-Encoding" -> "gzip",
        "Content-Length" -> "5913339"
      )

      val expected = "PUT\n4gJE4saaMU4BqNR0kLY+lw==\napplication/x-download\nTue, 27 Mar 2007 21:06:08 +0000\nx-amz-acl:public-read\nx-amz-meta-checksumalgorithm:crc32\nx-amz-meta-filechecksum:0x02661779\nx-amz-meta-reviewedby:joe@johnsmith.net,jane@johnsmith.net\n/static.johnsmith.net/db-backup.dat.gz"

      stringToSign("PUT", PathRequest, Some(contentMd5), Some(contentType),
        date, headers, "static.johnsmith.net:8080",
        s"http://static.johnsmith.net:8080/photos/puppy.jpg") must_== expected

    }
     */

    "be computed for 'GET /'" in {
      val date = "Wed, 28 Mar 2007 01:29:59 +0000"
      val headers = headerMap("Host" -> serverHost, "Date" -> date)

      val expected = "GET\n\n\nWed, 28 Mar 2007 01:29:59 +0000\n/"

      stringToSign("GET", PathRequest, None, None, date, headers, serverHost,
        s"http://$serverHost/") must_== expected
    }
  }

  "Calculated authentication signature" should {
    val signature1 = "5m+HAmc5JsrgyDelh9+a2dNrzN8="
    s"be '$signature1' for request #1" in {
      calculator.computeSignature(
        "GET\n\n\n\n" +
          "x-amz-date:Thu, 17 Nov 2005 18:49:58 GMT\n" +
          "x-amz-magic:abracadabra\n" +
          "/quotes/nelson") aka "signature" must beSuccessfulTry(signature1)
    }

    val signature2 = "jZNOcbfWmD/A/f3hSvVzXZjM2HU="
    s"be '$signature2' for request #2" in {
      calculator.computeSignature(
        "PUT\n" +
          "c8fdb181845a4ca6b8fec737b3581d76\n" +
          "text/html\n" +
          "Thu, 17 Nov 2005 18:49:58 GMT\n" +
          "x-amz-magic:abracadabra\n" +
          "x-amz-meta-author:foo@bar.com\n" +
          "/quotes/nelson") aka "signature" must beSuccessfulTry(signature2)
    }

    val signature3 = "vjbyPxybdZaNmGa+yT272YEAiv4="
    s"be '$signature3' for request #3" in {
      calculator.computeSignature(
        "GET\n\n\n1141889120\n/quotes/nelson").
        aka("signature") must beSuccessfulTry(signature3)
    }
  }

  // ---

  def headerMap(headers: (String, String)*): DefaultHttpHeaders =
    headers.foldLeft(new DefaultHttpHeaders()) {
      case (hs, (n, v)) => hs.add(n, v); hs
    }

  lazy val calculator = new SignatureCalculator(
    accessKey = "44CF9590006BF252F707",
    secretKey = "OtxrzxIsfpFjA7SwPzILwy8Bw21TLhquhboDYROV",
    "s3.amazonaws.com")
}

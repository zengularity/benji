package tests.benji

import akka.util.ByteString

import com.zengularity.benji.ws.ContentMD5

class CoreSpec extends org.specs2.mutable.Specification {
  "Core" title

  "MD5 digest" should {
    "be the expected one (in base64)" in {
      ContentMD5(ByteString.fromString("Hello World !!!", "UTF-8")).
        aka("MD5/base64") must_=== "SDG2HaRMa2UPAf9NTXzO8w=="

    }
  }
}

package tests.benji.google

import java.net.{ MalformedURLException, URI }

import org.specs2.mutable.Specification

import com.zengularity.benji.google.GoogleTransport
import com.zengularity.benji.URIProvider

import scala.util.Failure

class GoogleTransportSpec extends Specification {
  "GoogleTransport" title

  "GoogleTransport.apply using URIs" should {
    val filename = "gcs-test.json"
    val projectId = TestUtils.config.getString("google.storage.projectId")
    val application = s"benji-tests-${System identityHashCode this}"

    import TestUtils.WS

    "returns Failure when the provider fail" in {
      val exception: Throwable = new Exception("foo")
      implicit val provider = URIProvider[Throwable](Failure[URI])

      GoogleTransport(exception) must beFailedTry.withThrowable[Exception]("foo")
    }

    "returns Failure when given a null URI" in {
      GoogleTransport(null: URI) must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "returns Success when given a proper uri as String" in {
      GoogleTransport(s"google:classpath://$filename?application=$application&projectId=$projectId") must beSuccessfulTry
    }

    "returns Success when given a proper uri as URI" in {
      val uri = new URI(s"google:classpath://$filename?application=$application&projectId=$projectId")

      GoogleTransport(uri) must beSuccessfulTry
    }

    "returns Failure with wrong scheme" in {
      GoogleTransport(s"google:wrong://$filename?application=$application&projectId=$projectId") must beFailedTry.withThrowable[MalformedURLException]
    }

    "returns Failure without scheme prefix" in {
      GoogleTransport(s"classpath://$filename?application=$application&projectId=$projectId") must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "returns Failure when given a uri without application parameter" in {
      GoogleTransport(s"google:classpath://$filename?projectId=$projectId") must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "returns Failure when given a uri without projectId parameter" in {
      GoogleTransport(s"google:classpath://$filename?application=$application") must beFailedTry.withThrowable[IllegalArgumentException]
    }
  }

}

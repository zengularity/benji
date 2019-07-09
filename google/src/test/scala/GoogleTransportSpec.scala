package tests.benji.google

import java.net.{ MalformedURLException, URI }

import org.specs2.mutable.Specification

import com.zengularity.benji.google.GoogleTransport
import com.zengularity.benji.URIProvider

import com.zengularity.benji.google.tests.TestUtils

import scala.util.Failure

final class GoogleTransportSpec extends Specification {
  "GoogleTransport" title

  import TestUtils.ws

  val filename = "gcs-test.json"
  val projectId = TestUtils.config.getString("google.storage.projectId")
  val application = s"benji-tests-${System.identityHashCode(this).toString}"

  "Factory using URI" should {
    "succeed" >> {
      "when given a proper uri as String" in {
        GoogleTransport(s"google:classpath://$filename?application=$application&projectId=$projectId") must beSuccessfulTry
      }

      "when given a proper uri as URI" in {
        val uri = new URI(s"google:classpath://$filename?application=$application&projectId=$projectId")

        GoogleTransport(uri) must beSuccessfulTry
      }

      "with optional request timeout in URI" in {
        val uri = new URI(s"google:classpath://$filename?application=$application&projectId=$projectId&requestTimeout=123")

        GoogleTransport(uri) must beSuccessfulTry[GoogleTransport].like {
          case transport => transport.requestTimeout must beSome[Long].which {
            _ aka "request timeout" must_=== 123L
          } and {
            transport.disableGZip must beFalse // by default
          }
        }
      }

      "with GZip disabled in URI" in {
        val uri = new URI(s"google:classpath://$filename?application=$application&projectId=$projectId&disableGZip=true")

        GoogleTransport(uri) must beSuccessfulTry[GoogleTransport].like {
          case transport => transport.disableGZip must beTrue
        }
      }
    }

    "fail" >> {
      "when the provider fail" in {
        val exception: Throwable = new Exception("foo")
        implicit val provider = URIProvider[Throwable](Failure[URI])

        GoogleTransport(exception) must beFailedTry.
          withThrowable[Exception]("foo")
      }

      "when given a null URI" in {
        @SuppressWarnings(Array("org.wartremover.warts.Null"))
        @inline def test = GoogleTransport(null: URI)

        test must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "with wrong scheme" in {
        GoogleTransport(s"google:wrong://$filename?application=$application&projectId=$projectId") must beFailedTry.withThrowable[MalformedURLException]
      }

      "without scheme prefix" in {
        GoogleTransport(s"classpath://$filename?application=$application&projectId=$projectId") must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "when given a uri without application parameter" in {
        GoogleTransport(s"google:classpath://$filename?projectId=$projectId") must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "when given a uri without projectId parameter" in {
        GoogleTransport(s"google:classpath://$filename?application=$application") must beFailedTry.withThrowable[IllegalArgumentException]
      }

      "with invalid request timeout in URI" in {
        val uri = new URI(s"google:classpath://$filename?application=$application&projectId=$projectId&requestTimeout=AB")

        GoogleTransport(uri) must beFailedTry[GoogleTransport].
          withThrowable[IllegalArgumentException](
            "Invalid 'requestTimeout' parameter: AB")
      }

      "with value for 'disableGZip' in URI" in {
        val uri = new URI(s"google:classpath://$filename?application=$application&projectId=$projectId&disableGZip=Foo")

        GoogleTransport(uri) must beFailedTry[GoogleTransport].
          withThrowable[IllegalArgumentException](
            "Invalid 'disableGZip' parameter: Foo")
      }
    }
  }
}

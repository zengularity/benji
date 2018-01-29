package tests.benji.vfs

import java.net.URI

import org.specs2.mutable.Specification

import com.zengularity.benji.vfs.VFSTransport
import com.zengularity.benji.URIProvider

import scala.util.Failure

class VFSTransportSpec extends Specification {
  "VFSTransport" title

  "Factory using URs" should {
    "return Failure when the provider fail" in {
      val exception: Throwable = new Exception("foo")
      implicit val provider = URIProvider[Throwable](Failure[URI])

      VFSTransport(exception) must beFailedTry.withThrowable[Exception]("foo")
    }

    "return Failure when given a null URI" in {
      @SuppressWarnings(Array("org.wartremover.warts.Null"))
      def throwNull = VFSTransport(null: URI)

      throwNull must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "return Success when given a proper uri as String" in {
      VFSTransport("vfs:file:///home/someuser/somedir") must beSuccessfulTry
    }

    "return Success when given a proper uri as URI" in {
      val uri = new URI("vfs:file:///home/someuser/somedir")

      VFSTransport(uri) must beSuccessfulTry
    }

    "return Failure with wrong scheme" in {
      VFSTransport("vfs:wrong://path") must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "return Failure without scheme prefix" in {
      VFSTransport("file:///home/someuser/somedir") must beFailedTry.withThrowable[IllegalArgumentException]
    }

    "return Success when we use temporary as uri" in {
      VFSTransport("vfs:temporary") must beSuccessfulTry
    }
  }

}

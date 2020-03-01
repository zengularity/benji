package tests.benji.s3

import java.net.URI

import com.zengularity.benji.spi.{ Injector, Registry, StorageScheme }

import com.zengularity.benji.spi.{ Registry, StorageScheme }

import com.zengularity.benji.s3.{ S3Factory, S3Scheme, WSS3 }

import com.zengularity.benji.s3.tests.TestUtils

class S3FactorySpec extends org.specs2.mutable.Specification {
  "S3 factory" title

  "S3 storage" should {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    lazy val scheme = loader.iterator.next()
    def factory = scheme.factoryClass.getDeclaredConstructor().newInstance()

    {
      val uri = TestUtils.virtualHostStyleUrl

      s"be resolved from ${uri take 8}..." in {
        scheme must beAnInstanceOf[S3Scheme] and {
          factory(WSInjector, new URI(uri)) must beAnInstanceOf[WSS3]
        }
      }
    }

    {
      val uri = new URI("foo:s3")

      s"not be resolved from ${uri.toString}" in {
        factory(WSInjector, uri) must throwA[Exception](
          "Expected URI with scheme.*")
      }
    }
  }

  "Registry" should {
    val reg = Registry.getInstance
    val scheme = "s3"

    "find the registered schemes" in {
      reg.schemes must contain(atLeast(scheme))
    }

    s"resolve the factory for $scheme" in {
      reg.factoryClass(scheme) must beSome(classOf[S3Factory])
    }
  }

  // ---

  import play.api.libs.ws.ahc.StandaloneAhcWSClient

  private implicit def materializer = TestUtils.materializer

  object WSInjector extends Injector {
    private val WS = classOf[StandaloneAhcWSClient]

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def instanceOf[T](cls: Class[T]): T = cls match {
      case WS => StandaloneAhcWSClient().asInstanceOf[T]
      case _ => ???
    }
  }
}

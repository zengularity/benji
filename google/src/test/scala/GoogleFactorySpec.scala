package tests.benji.google

import java.net.URI

import com.zengularity.benji.google.{
  GoogleFactory,
  GoogleScheme,
  GoogleStorage
}
import com.zengularity.benji.google.tests.TestUtils
import com.zengularity.benji.spi.{ Injector, Registry, StorageScheme }

final class GoogleFactorySpec extends org.specs2.mutable.Specification {
  "Google factory".title

  "Google storage" should {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    lazy val scheme = loader.iterator.next()
    def factory = scheme.factoryClass.getDeclaredConstructor().newInstance()

    {
      val uri = new URI(TestUtils.configUri)

      s"be resolved from ${uri.toString}" in {
        scheme must beAnInstanceOf[GoogleScheme] and {
          factory(WSInjector, uri) must beAnInstanceOf[GoogleStorage]
        }
      }
    }

    {
      val uri = new URI("foo:google")

      s"not be resolved from ${uri.toString}" in {
        factory(WSInjector, uri) must throwA[Exception](
          "Expected URI with scheme.*"
        )
      }
    }
  }

  "Registry" should {
    val reg = Registry.getInstance
    val scheme = "google"

    "find the registered schemes" in {
      reg.schemes must contain(atLeast(scheme))
    }

    s"resolve the factory for $scheme" in {
      reg.factoryClass(scheme) must beSome(classOf[GoogleFactory])
    }
  }

  // ---

  import play.api.libs.ws.ahc.StandaloneAhcWSClient

  private implicit def materializer: akka.stream.Materializer =
    TestUtils.materializer

  object WSInjector extends Injector {
    private val WS = classOf[StandaloneAhcWSClient]

    @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
    def instanceOf[T](cls: Class[T]): T = cls match {
      case WS => StandaloneAhcWSClient().asInstanceOf[T]
      case _  => ???
    }
  }
}

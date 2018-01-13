package tests.benji.google

import java.net.URI

import play.api.inject.guice.{ GuiceApplicationBuilder, GuiceableModule }

import com.zengularity.benji.spi.{ Registry, StorageFactory, StorageScheme }

import com.zengularity.benji.google.{
  GoogleFactory,
  GoogleScheme,
  GoogleStorage
}

class GoogleFactorySpec extends org.specs2.mutable.Specification {
  "Google factory" title

  "Google storage" should {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    lazy val scheme = loader.iterator.next()

    {
      val uri = new URI(TestUtils.configUri)

      s"be resolved from $uri" in {
        scheme must beAnInstanceOf[GoogleScheme] and {
          withInjected(scheme.factoryClass) {
            _(uri) must beAnInstanceOf[GoogleStorage]
          }
        }
      }
    }

    {
      val uri = new URI("foo:google")

      s"not be resolved from $uri" in {
        withInjected(scheme.factoryClass) {
          _(uri) must throwA[Exception]("Expected URI with scheme.*")
        }
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

  import play.api.{ Configuration, Environment }
  import play.api.inject.{ Binding, Module }
  import play.api.libs.ws.StandaloneWSClient

  implicit def materializer = TestUtils.materializer

  object TestModule extends Module {
    def bindings(env: Environment, config: Configuration): Seq[Binding[_]] =
      Seq(bind[StandaloneWSClient].
        to(play.api.libs.ws.ahc.StandaloneAhcWSClient()))
  }

  def withInjected[T](factoryCls: Class[_ <: StorageFactory])(f: StorageFactory => T): T = {
    val appBuilder = new GuiceApplicationBuilder().
      bindings(GuiceableModule.fromPlayModule(TestModule)).build

    f(appBuilder.injector.instanceOf(factoryCls))
  }
}

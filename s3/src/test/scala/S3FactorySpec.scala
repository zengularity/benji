package tests.benji.s3

import java.net.URI

import play.api.inject.guice.{ GuiceApplicationBuilder, GuiceableModule }

import com.zengularity.benji.spi.{ Registry, StorageFactory, StorageScheme }

import com.zengularity.benji.s3.{
  S3Factory,
  S3Scheme,
  WSS3
}

class S3FactorySpec extends org.specs2.mutable.Specification {
  "S3 factory" title

  "S3 storage" should {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    lazy val scheme = loader.iterator.next()

    {
      val uri = TestUtils.virtualHostStyleURL

      s"be resolved from ${uri take 8}..." in {
        scheme must beAnInstanceOf[S3Scheme] and {
          withInjected(scheme.factoryClass) {
            _(new URI(uri)) must beAnInstanceOf[WSS3]
          }
        }
      }
    }

    {
      val uri = new URI("foo:s3")

      s"not be resolved from $uri" in {
        withInjected(scheme.factoryClass) {
          _(uri) must throwA[Exception]("Expected URI with scheme.*")
        }
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

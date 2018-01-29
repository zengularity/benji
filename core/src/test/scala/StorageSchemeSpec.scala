package tests.benji

import java.net.URI

import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.spi.{
  Injector,
  Registry,
  StorageFactory,
  StorageScheme
}

class StorageSchemeSpec extends org.specs2.mutable.Specification {
  "Scheme aware factory" title

  "Dummy storage" should {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    lazy val scheme = loader.iterator.next()
    lazy val service = scheme.factoryClass.
      getDeclaredConstructor().newInstance()

    {
      val uri = new URI("dummy:foo")

      s"be resolved from $uri" in {
        service(DummyInjector, uri) must_== DummyStorage
      }
    }

    {
      val uri = new URI("foo:dummy")

      s"not be resolved $uri" in {
        service(DummyInjector, uri) must throwA[IllegalArgumentException]("foo")
      }
    }
  }

  "Registry" should {
    val reg = Registry.getInstance

    "find the registered schemes" in {
      reg.schemes must contain(exactly("dummy"))
    }

    {
      val scheme = "dummy"

      s"resolve the factory for $scheme" in {
        reg.factoryClass(scheme) must beSome(classOf[DummyFactory])
      }
    }
  }
}

sealed class DummyStorage extends ObjectStorage {
  def withRequestTimeout(timeout: Long): ObjectStorage = this
  def buckets: BucketsRequest = ???
  def bucket(name: String): com.zengularity.benji.BucketRef = ???
}

object DummyStorage extends DummyStorage

object DummyInjector extends Injector {
  def instanceOf[T](cls: Class[T]): T = ???
}

final class DummyScheme extends StorageScheme {
  val scheme = "dummy"

  val factoryClass: Class[_ <: StorageFactory] = classOf[DummyFactory]
}

final class DummyFactory extends StorageFactory {
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def apply(injector: Injector, uri: URI): ObjectStorage = {
    if (uri.getScheme == "dummy") DummyStorage
    else throw new IllegalArgumentException("foo")
  }
}

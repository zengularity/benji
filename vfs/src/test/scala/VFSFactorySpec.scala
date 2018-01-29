package tests.benji.vfs

import java.net.URI

import com.zengularity.benji.spi.{ Registry, StorageScheme }
import com.zengularity.benji.vfs.{ VFSFactory, VFSStorage }

import tests.benji.DummyInjector

class VFSFactorySpec extends org.specs2.mutable.Specification {
  "VFS factory" title

  "VFS storage" should {
    val loader = java.util.ServiceLoader.load(classOf[StorageScheme])
    lazy val scheme = loader.iterator.next()
    lazy val service = scheme.factoryClass.
      getDeclaredConstructor().newInstance()

    {
      val uri = new URI("vfs:tmp:///")

      s"be resolved from $uri" in {
        service must beAnInstanceOf[VFSFactory] and {
          service(DummyInjector, uri) must beAnInstanceOf[VFSStorage]
        }
      }
    }

    {
      val uri = new URI("foo:vfs")

      s"not be resolved from $uri" in {
        service(DummyInjector, uri) must throwA[Exception](
          "Expected URI with scheme.*")
      }
    }
  }

  "Registry" should {
    val reg = Registry.getInstance
    val scheme = "vfs"

    "find the registered schemes" in {
      reg.schemes must contain(atLeast(scheme))
    }

    s"resolve the factory for $scheme" in {
      reg.factoryClass(scheme) must beSome(classOf[VFSFactory])
    }
  }
}

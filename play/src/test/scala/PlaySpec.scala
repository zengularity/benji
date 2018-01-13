package tests.benji.play

import com.google.inject

import play.api.inject.guice.GuiceApplicationBuilder

import play.api.test.Helpers.running

import com.zengularity.benji.ObjectStorage

import com.zengularity.benji.vfs.VFSStorage

import tests.benji.DummyStorage

import play.modules.benji.{ BenjiFromContext, NamedStorage, TestUtils }

import org.specs2.specification.core.Fragments

class PlaySpec extends org.specs2.mutable.Specification {
  "Play integration" title

  sequential

  import PlayUtil.configure

  "ObjectStorage" should {
    "not be resolved if the module is not enabled" in {
      val appBuilder = new GuiceApplicationBuilder().build

      appBuilder.injector.instanceOf[ObjectStorage].
        aka("resolution") must throwA[inject.ConfigurationException]
    }

    "be resolved" >> {
      "as default instance if the module is enabled" in {
        System.setProperty("config.resource", "test1.conf")

        running(configure _) {
          _.injector.instanceOf[ObjectStorage] must beAnInstanceOf[VFSStorage]
        }
      }

      "as multiple instances if the module is enabled" in {
        System.setProperty("config.resource", "test3.conf")

        val names = Seq("default", "bar", "lorem", "ipsum")

        names.map { name =>
          configuredAppBuilder.injector.instanceOf[ObjectStorage](
            TestUtils.bindingKey(name))
        } must contain[ObjectStorage](
          beAnInstanceOf[VFSStorage]).forall
      }
    }

    "be injected" >> {
      "as default instance" in {
        System.setProperty("config.resource", "test1.conf")

        running(configure _) { app =>
          app.injector.instanceOf[InjectDefault].storage.
            aka("storage") must beAnInstanceOf[VFSStorage]
        }
      }

      "as instance named 'default'" in {
        System.setProperty("config.resource", "test1.conf")

        running(configure _) { app =>
          app.injector.instanceOf[InjectDefaultNamed].storage.
            aka("storage") must beAnInstanceOf[VFSStorage]
        }
      }

      "as instance named 'foo'" in {
        System.setProperty("config.resource", "test2.conf")

        running() {
          _.injector.instanceOf[InjectFooNamed].storage.
            aka("storage") must beAnInstanceOf[DummyStorage]
        }
      }

      "as multiple default and named instance" in {
        System.setProperty("config.resource", "test3.conf")

        running(configure _) {
          _.injector.instanceOf[InjectMultiple].storages.
            aka("storages") must contain[ObjectStorage](
              beAnInstanceOf[VFSStorage]).forall
        }
      }
    }

    "be initialized from custom application context" >> {
      def benji(n: String = "default") = {
        val apiFromCustomCtx = new BenjiFromContext(PlayUtil.context, n) {
          lazy val router = play.api.routing.Router.empty

          override lazy val httpFilters =
            Seq.empty[play.api.mvc.EssentialFilter]
        }

        apiFromCustomCtx.benji
      }

      "successfully with default URI" in {
        System.setProperty("config.resource", "test1.conf")

        benji() must beAnInstanceOf[VFSStorage]
      }

      "successfully with other URI" in {
        System.setProperty("config.resource", "test2.conf")

        benji("foo") must beAnInstanceOf[DummyStorage]
      }

      "successfully from composite configuration" >> {
        Fragments.foreach(Seq[(String, () => ObjectStorage)](
          "<default>" -> (() => benji()),
          "default" -> (() => benji("default")),
          "bar" -> (() => benji("bar")),
          "lorem" -> (() => benji("lorem")),
          "ipsum" -> (() => benji("ipsum")))) {
          case (label, storage) =>
            s"for $label" in {
              System.setProperty("config.resource", "test3.conf")
              storage() must beAnInstanceOf[VFSStorage]
            }
        }
      }

      "successfully with URI with prefix" in {
        System.setProperty("config.resource", "test4.conf")

        benji("default") must beAnInstanceOf[DummyStorage]
      }
    }
  }

  // ---

  def configuredAppBuilder = {
    val env = play.api.Environment.simple(mode = play.api.Mode.Test)
    val config = play.api.Configuration.load(env)
    val modules = config.getOptional[Seq[String]]("play.modules.enabled").
      getOrElse(Seq.empty[String])

    new GuiceApplicationBuilder().
      configure("play.modules.enabled" -> (modules :+
        "play.modules.benji.BenjiModule")).build
  }
}

import javax.inject.Inject

class InjectDefault @Inject() (val storage: ObjectStorage)

class InjectDefaultNamed @Inject() (
  @NamedStorage("default") val storage: ObjectStorage)

class InjectFooNamed @Inject() (
  @NamedStorage("foo") val storage: ObjectStorage)

class InjectMultiple @Inject() (
  val defaultStorage: ObjectStorage,
  @NamedStorage("default") val namedDefault: ObjectStorage,
  @NamedStorage("bar") val bar: ObjectStorage,
  @NamedStorage("lorem") val lorem: ObjectStorage,
  @NamedStorage("ipsum") val ipsum: ObjectStorage) {
  @inline def storages = Vector(defaultStorage, namedDefault, bar, lorem, ipsum)
}

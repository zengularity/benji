# Use Benji with Play Framework.

This documentation is about the integration of Benji with [Play Framework](https://playframework.com/).

## Add to your dependencies

The latest version of this plugin is for Play 2.6+, and can be enabled by adding the following dependency in your `build.sbt`.

```ocaml
// only for Play 2.6.x
libraryDependencies ++= Seq(
  "com.zengularity" %% "benji-play" % "{{site.latest_release}}-play26"
)

// only for Play 2.7.x
libraryDependencies ++= Seq(
  "com.zengularity" %% "benji-play" % "{{site.latest_release}}-play27"
)

// only for Play 2.8.x
libraryDependencies ++= Seq(
  "com.zengularity" %% "benji-play" % "{{site.latest_release}}-play28"
)
```

Then it's also required to enable the wanted backend, e.g. for S3:

```ocaml
val benjiVer = {{site.latest_release}}

libraryDependencies ++= Seq("play", "s3").map { mod =>
  "com.zengularity" %% s"benji-${mod}" % benjiVer,
}
```

## Setup

The dependency injection can be configured, so that the your controllers can be given `ObjectStorage` instances.

To do so, first, add the line bellow to `application.conf`:

```ocaml
play.modules.enabled += "play.modules.benji.BenjiModule"
```

Then use the Play's dependency injection mechanism to resolve an instance of `ObjectStorage`, as an interface to the configured storage.

```scala
import javax.inject.Inject

import play.api.mvc.{ AbstractController, ControllerComponents }

import com.zengularity.benji.ObjectStorage

class MyController @Inject() (
  components: ControllerComponents,
  val storage: ObjectStorage
) extends AbstractController(components) {

  // ...
}
```

## Compile-time dependency injection

The trait `BenjiComponents` define the contract for [compile-time dependency injection](https://playframework.com/documentation/latest/ScalaCompileTimeDependencyInjection).

The class `BenjiFromContext` is one implementation of this trait to ease compile-time integration.

```scala
import play.api.ApplicationLoader

import com.zengularity.benji.ObjectStorage

import play.modules.benji._

class MyComponent1(
  context: ApplicationLoader.Context,
  name: String // Name of the storage config (see next section)
) extends BenjiFromContext(context, name) {
  // can be a Controller, a Play custom Module, ApplicationLoader ...

  def httpFilters: Seq[play.api.mvc.EssentialFilter] = ???
  def router: play.api.routing.Router = ???
}

def foo(my: MyComponent1): ObjectStorage = my.benji // resolved storage
```

> When using Play dependency injection for a controller, the [injected routes need to be enabled](https://www.playframework.com/documentation/latest/ScalaRouting#Dependency-Injection) by adding `routesGenerator := InjectedRoutesGenerator` to your build.

In case the component class must implements several Play contracts, then the base trait `BenjiComponentsFromInjector`.

```scala
import play.api.ApplicationLoader

import com.zengularity.benji.ObjectStorage

import play.modules.benji._

abstract class OtherComponentsFromContext(
  context: ApplicationLoader.Context
) extends play.api.BuiltInComponentsFromContext(context) {
  // other components
}

class MyComponent2(
  context: ApplicationLoader.Context,
  val name: String, // Name of the storage config (see next section)
  val parsedUri: java.net.URI // Benji URI for this component
) extends OtherComponentsFromContext(context) with BenjiComponentsWithInjector {
  // can be a Controller, a Play custom Module, ApplicationLoader ...

  def benjiInjector = new play.modules.benji.PlayInjector(injector)
  def httpFilters: Seq[play.api.mvc.EssentialFilter] = ???
  def router: play.api.routing.Router = ???
}

def bar(my: MyComponent2): ObjectStorage = my.benji // resolved storage
```

**Multiple storages**

In your Play application, you can use Benji with multiple storage backends (possibly with different kinds of storage and/or account), using the `@NamedStorage` annotation.

Consider the following configuration, with several storage URIs.

```
# The default URI
benji.uri = "s3:https://..."

# Another one, named with 'bar'
benji.bar.uri = "vfs:tmp:///"
```

Then the dependency injection can select the instances using the names.

```scala
import javax.inject.Inject

import com.zengularity.benji.ObjectStorage

import play.modules.benji.NamedStorage

class MyComponent @Inject() (
  val defaultStorage: ObjectStorage, // corresponds to 'benji.uri'
  @NamedStorage("bar") val barStorage: ObjectStorage // 'benji.bar'
) {

}
```

### Configure your storage access

This module reads the connection properties from the `application.conf` and gives you an easy access to the connected database.

You can use the URI syntax to point to your storage:

```
benji.uri = "s3:https://..."
```

Each storage module provide a scheme support (e.g. `s3:`, `vfs:`, ...; See modules documentation).

This is especially helpful on platforms like Heroku, where the add-ons publish the storage URI in a single environment variable (e.g. `STORAGE_URI`).

```
benji.uri = ${?STORAGE_URI}
```

To configure a storage instance different from the default one (corresponding the `@NamedStorage("ANY_NAME")` annotation), the key must be of the form `mongodb.ANY_NAME.uri`.

```
mongodb.ANY_NAME.uri = "..."
```

> The Google and S3 modules also need that a `StandaloneWSClient` is provised in Play context.

## Examples

*See the [examples](https://github.com/zengularity/benji/tree/master/examples) directory*

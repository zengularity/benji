# Play Framework Integration

This documentation covers using Benji with [Play Framework](https://playframework.com/).

## Adding dependencies

Benji supports Play Framework 2.6 and later. Add the integration module and your chosen storage backend to `build.sbt`:

```scala
// Select your Play version
libraryDependencies ++= Seq(
  "com.zengularity" %% "benji-play" % "{{site.latest_release}}-play26" // Play 2.6.x
  // "com.zengularity" %% "benji-play" % "{{site.latest_release}}-play27" // Play 2.7.x
  // "com.zengularity" %% "benji-play" % "{{site.latest_release}}-play28" // Play 2.8.x
)

// Add your storage backend (example: S3)
val benjiVer = "{{site.latest_release}}"
libraryDependencies += "com.zengularity" %% "benji-s3" % benjiVer
```

## Module setup

Enable the Benji module in `application.conf`:

```conf
play.modules.enabled += "play.modules.benji.BenjiModule"
```

Then inject `ObjectStorage` into your controllers:

```scala
import javax.inject.Inject

import play.api.mvc.{ AbstractController, ControllerComponents }

import com.zengularity.benji.ObjectStorage

class MyController @Inject() (
  components: ControllerComponents,
  storage: ObjectStorage
) extends AbstractController(components) {
  // use storage ...
}
```

## Compile-time dependency injection (optional)

For compile-time dependency injection, use the `BenjiComponents` trait or `BenjiFromContext` helper class:

```scala
import play.api.ApplicationLoader

import com.zengularity.benji.ObjectStorage

import play.modules.benji._

class MyComponent1(
  context: ApplicationLoader.Context,
  name: String // Storage config name (see Configuration section)
) extends BenjiFromContext(context, name) {
  def httpFilters: Seq[play.api.mvc.EssentialFilter] = ???
  def router: play.api.routing.Router = ???
}

val storage: ObjectStorage = MyComponent1(...).benji

For more complex scenarios with multiple Play traits, use `BenjiComponentsWithInjector`:

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

### Configuring storage

The module reads connection properties from `application.conf` using URI syntax:

```
benji.uri = "s3:https://..."
```

Each storage module provide a scheme support (e.g. `s3:`, `vfs:`, ...; See modules documentation).

This is especially helpful on platforms like Heroku, where the add-ons publish the storage URI in a single environment variable (e.g. `STORAGE_URI`).

```
benji.uri = ${?STORAGE_URI}
```

To configure a storage instance different from the default one (corresponding the `@NamedStorage("ANY_NAME")` annotation), use `benji.ANY_NAME.uri`:

```conf
benji.ANY_NAME.uri = "..."
```

> The Google Cloud Storage and S3 modules require a `StandaloneWSClient` in your Play context.

## Examples

*See the [examples](https://github.com/zengularity/benji/tree/master/examples) directory*

# Benji Google usage

Usage of the Benji module for [Google Cloud Storage](https://cloud.google.com/products/storage/).

The first step is a add the dependency in your `build.sbt`.

```ocaml
libraryDependencies += "com.zengularity" %% "benji-google" % "{{site.latest_release}}"

// If Play WS is not yet provided:
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.1.3",
  "com.typesafe.play" %% "play-ws-standalone-json" % "1.1.3")

resolvers ++= Seq(
  "Entrepot Releases" at "https://raw.github.com/zengularity/entrepot/master/releases",
  "Entrepot Snapshots" at "https://raw.github.com/zengularity/entrepot/master/snapshots"
)
```

Then, the Google Storage client can be used as following in your code.

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.{ Bucket, Object }
import com.zengularity.benji.google.{
  GoogleBucketRef, GoogleStorage, GoogleTransport
}

// Settings
val projectId = "google-project-123456"
val appName = "Foo"

def credential: GoogleCredential = GoogleCredential.fromStream(
  new java.io.FileInputStream("/path/to/google-credential.json"))

def sample1(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  // WSClient must be available to init the GoogleTransport
  implicit def ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  def gt: GoogleTransport = GoogleTransport(credential, projectId, appName)
  val gcs = GoogleStorage(gt)

  val buckets: Future[List[Bucket]] = gcs.buckets.collect[List]

  buckets.flatMap {
    _.headOption.fold(Future.successful(println("No found"))) { firstBucket =>
      val bucketRef: GoogleBucketRef = gcs.bucket(firstBucket.name)
      val objects: Source[Object, NotUsed] = bucketRef.objects()

      objects.runWith(Sink.foreach[Object] { obj =>
        println(s"- ${obj.name}")
      }).map(_ => {})
      
      /* Get object list with specified batch size, by default it's 1000 */
      val allObjects: Future[List[com.zengularity.benji.Object]] = bucketRef.objects.withBatchSize(100).collect[List]()
      allObjects.map(_.foreach(obj => println(s"- ${obj.name}")))
    }
  }
}
```

## Google client configuration

There are several factories to create a Google `ObjectStorage` client, either passing parameters separately, or using a configuration URI.

```scala
import akka.stream.Materializer

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.google._

def sample2a(implicit m: Materializer): GoogleStorage = {
  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  // Settings
  val projectId = "google-project-123456"
  val appName = "Foo"

  def credential = GoogleCredential.fromStream(
    new java.io.FileInputStream("/path/to/google-credential.json"))

  def gt: GoogleTransport = GoogleTransport(credential, projectId, appName)

  GoogleStorage(gt)
}

// Using configuration URI
@SuppressWarnings(Array("org.wartremover.warts.TryPartial"))
def sample2b(implicit m: Materializer): GoogleStorage = {
  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  val configUri = "google:classpath://resource-for-credentials.json?application=Foo&projectId=google-project-123456"

  GoogleStorage(GoogleTransport(configUri).get)
}
```

The main settings are:

- *credentials*: An URI to a JSON file representing the [credentials to access Google Cloud Storage](https://cloud.google.com/storage/docs/authentication#generating-a-private-key). To use resource accessible through the application classpath, the scheme `classpath:` can be used (e.g. `classpath://foo.json` will try to load the credentials with `getResource("foo.json")`). For credentials stored in a file outside the JVM, the scheme `file:` is useful.
- *application*: The name of application.
- *projectId*: The unique ID for which the credentials are registered in the Google Cloud.

The format for the configuration URIs is the following:

    google:${credentialUri}?application=${application}&projectId=${projectId}

The optional parameters `requestTimeout` and `disableGZip` can also be specified in the query string of such URI:

    ...&projectId=${projectId}&requestTimeout=${timeInMilliseconds}&disableGZip=${falseByDefault}

## Troubleshoot

    Invalid JWT: Token must be a short-lived token and in a reasonable timeframe

The date/time on the client side is [out of sync](http://stackoverflow.com/a/36201957/3347384).

## FAQ

**Naming Restrictions:** Google Cloud [bucket naming restriction](https://cloud.google.com/storage/docs/naming) applies (3-63 characters long, only lower cases, numbers, underscores, dots and dashes, etc.), it's recommended to use DNS-compliant bucket names.

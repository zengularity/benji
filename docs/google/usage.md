# Google Cloud Storage with Benji

This guide covers using Benji with [Google Cloud Storage](https://cloud.google.com/products/storage/).

## Setup

Add the Google Cloud Storage module and Play WS to your `build.sbt`:

```ocaml
libraryDependencies += "com.zengularity" %% "benji-google" % "{{site.latest_release}}"

// Play WS standalone
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.2.6",
  "com.typesafe.play" %% "play-ws-standalone-json" % "2.2.6"
)
```

## Basic usage

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import com.google.auth.oauth2.GoogleCredentials

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.{ Bucket, Object }
import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport }

// Settings
val projectId = "google-project-123456"
val appName = "Foo"

def credential: GoogleCredentials = GoogleCredentials.fromStream(
  new java.io.FileInputStream("/path/to/google-credential.json"))

def sample1(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  // WSClient must be available to init the GoogleTransport
  implicit def ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  def gt: GoogleTransport = GoogleTransport(credential, projectId, appName)
  val gcs = GoogleStorage(gt)

  val buckets: Future[List[Bucket]] = gcs.buckets.collect[List]()

  buckets.flatMap {
    _.headOption.fold(Future.successful(println("No found"))) { firstBucket =>
      val bucketRef = gcs.bucket(firstBucket.name)
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

## Google Cloud Storage client configuration

There are several factory methods to create a Google `ObjectStorage`, either with explicit parameters or using a configuration URI:

```scala
import akka.stream.Materializer

import com.google.auth.oauth2.GoogleCredentials

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.google._

def sample2a(implicit m: Materializer): GoogleStorage = {
  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  // Settings
  val projectId = "google-project-123456"
  val appName = "Foo"

  def credential = GoogleCredentials.fromStream(
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

The main configuration parameters are:

- **credentials** — URI to a JSON credentials file (see [Google Cloud authentication](https://cloud.google.com/storage/docs/authentication#generating-a-private-key))
  - Use `classpath://` for resources in your classpath (e.g., `classpath://creds.json`)
  - Use `file://` for files on disk
- **application** — Your application name
- **projectId** — Your Google Cloud project ID

URI configuration format:

```
google://${credentialUri}?application=${application}&projectId=${projectId}
```

Optional query parameters:

- `requestTimeout` — Request timeout in milliseconds
- `disableGZip` — Disable gzip compression (default: false)

## Troubleshooting

### Invalid JWT token error

```
Invalid JWT: Token must be a short-lived token and in a reasonable timeframe
```

This indicates your client's date/time is out of sync. Solution: Synchronize your system clock with an NTP server.

### Bucket naming restrictions

Google Cloud Storage [bucket naming rules](https://cloud.google.com/storage/docs/naming):

- 3–63 characters
- Lowercase letters, numbers, underscores, dots, and dashes only
- DNS-compliant names recommended

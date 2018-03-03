# Google Benji

Benji module for Google Cloud Storage.

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample) and `src/test/resources/gcs-test.json` files.

    sbt test

**Requirements:**

- A JDK 1.8+ is required.
- [Play Standalone WS](https://github.com/playframework/play-ws) must be provided; Tested with version 1.1.3.

## Usage

In your `build.sbt` (or `project/Build.scala`):

```
libraryDependencies += "com.zengularity" %% "benji-google" % "VERSION"

// If Play WS is not yet provided:
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.1.3",
  "com.typesafe.play" %% "play-ws-standalone-json" % "1.1.3")
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
import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport }

// Settings
val projectId = "google-project-123456"
val appName = "Foo"

def credential = GoogleCredential.fromStream(
  new java.io.FileInputStream("/path/to/google-credential.json"))

def sample1(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  // WSClient must be available to init the GoogleTransport
  implicit def ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  implicit def gt: GoogleTransport =
    GoogleTransport(credential, projectId, appName)

  val gcs = GoogleStorage()
  val buckets: Future[List[Bucket]] = gcs.buckets.collect[List]

  buckets.flatMap {
    _.headOption.fold(Future.successful(println("No found"))) { firstBucket =>
      val bucketRef = gcs.bucket(firstBucket.name)
      val objects: Source[Object, NotUsed] = bucketRef.objects()

      objects.runWith(Sink.foreach[Object] { obj =>
        println(s"- ${obj.name}")
      }).map(_ => {})
    }
  }
}
```

## Troubleshoot

    Invalid JWT: Token must be a short-lived token and in a reasonable timeframe

The date/time on the client side is [out of sync](http://stackoverflow.com/a/36201957/3347384).

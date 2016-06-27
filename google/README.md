# Google Cabinet

Cabinet module for Google Cloud Storage.

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

> A JDK 1.8+ is required.

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate `src/test/resources/local.conf` and `src/test/resources/gcs-test.json` files.

    sbt test

## Usage

In your `build.sbt` (or `project/Build.scala`):

```
libraryDependencies ++= Seq(
  "com.zengularity" %% "cabinet-google" % "VERSION"
)
```

Then, the Google Storage client can be used as following in your code.

```scala
import scala.concurrent.{ Future, ExecutionContext }
// As the storage operations are async, needs an ExecutionContext

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential

import play.api.libs.ws.WSClient
import play.api.libs.iteratee.{ Enumerator, Iteratee }

import com.zengularity.storage.{ Bucket, Object }
import com.zengularity.google.{ GoogleStorage, GoogleTransport }

// Settings
val projectId = "google-project-123456"
val appName = "Foo"

def credential = GoogleCredential.fromStream(
  new java.io.FileInputStream("/path/to/google-credential.json"))

// WSClient must be available to init the GoogleTransport
implicit def ws: WSClient = com.zengularity.ws.WS.client()

implicit def gt: GoogleTransport =
  GoogleTransport(credential, projectId, appName)

def sample1(implicit ec: ExecutionContext): Unit = {
  val gcs = GoogleStorage()
  val buckets: Future[List[Bucket]] = gcs.buckets.collect[List]

  buckets.flatMap {
    _.headOption.fold(Future.successful(println("No found"))) { firstBucket =>
      val bucketRef = gcs.bucket(firstBucket.name)
      val objects: Enumerator[Object] = bucketRef.objects()

      objects |>>> Iteratee.foreach { obj =>
        println(s"- ${obj.name}")
      }
    }
  }
}
```

## Troubleshoot

    Invalid JWT: Token must be a short-lived token and in a reasonable timeframe

The date/time on the client side is [out of sync](http://stackoverflow.com/a/36201957/3347384).

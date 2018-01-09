# S3 Benji

This library is a Scala client for object storage (e.g. S3/Amazon, S3/CEPH).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample).

    sbt test

**Requirements:**

- A JDK 1.8+ is required.
- [Play Standalone WS](https://github.com/playframework/play-ws) must be provided; Tested with version 1.1.3.

## Usage

In your `build.sbt` (or the `project/Build.scala`):

```
libraryDependencies += "com.zengularity" %% "benji-s3" % "VERSION"

// If Play WS is not yet provided:
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "1.1.3",
  "com.typesafe.play" %% "play-ws-standalone-xml" % "1.1.3")
```

Then, the S3 client can be used as following in your code.

```scala
import java.nio.file.Paths

import scala.concurrent.{ ExecutionContext, Future }

import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.{ FileIO, Sink, Source }

import play.api.libs.ws.ahc.StandaloneAhcWSClient
import play.api.libs.ws.DefaultBodyWritables._

import com.zengularity.benji.s3._

def sample1(implicit m: Materializer): Unit = {
  implicit def ec: ExecutionContext = m.executionContext

  // WSClient must be available in the implicit scope;
  // Here a default/standalone instance is declared
  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  val s3: WSS3 = S3("accessKey", "secretKey", "http", "hostAndPort")
  // See "S3 Client configuration" section for more ways create and configure a WSS3

  val bucket: WSS3BucketRef = s3.bucket("aBucket")

  // Upload

  /* input */
  val path = Paths.get("/path/to/local/file")
  lazy val data: Source[ByteString, _] = FileIO.fromPath(path)

  /* target object */
  val newObj = bucket.obj("newObject.ext")

  /* declare the upload pipeline */
  val upload: Sink[ByteString, Future[Long]] =
    newObj.put[ByteString, Long](0L) { (acc, chunk) =>
      println(s"uploading ${chunk.size} bytes")
      Future.successful(acc + chunk.size)
    }

  (data runWith upload).onComplete {
    case res => println(s"Upload result: $res")
  }
  
  /* Get objects list */
  val objects: Future[List[com.zengularity.benji.Object]] = bucket.objects.collect[List]()
  objects.map(_.foreach(obj => println(s"- ${obj.name}")))
  
  /* Get object list with specified batch size, by default it 1000 */
  val allObjects: Future[List[com.zengularity.benji.Object]] = bucket.objects.withBatchSize(100).collect[List]()
  allObjects.map(_.foreach(obj => println(s"- ${obj.name}")))

  // Take care to release the underlying resources
  ws.close()
}
```

## Tests

To run the compliance tests for this module, you have to go through the following steps.

*#1* Copy the file [`src/test/resources/local.conf.sample`](src/test/resources/local.conf.sample) as `src/test/resources/local.conf`.

*#2* Edit the settings in this `src/test/resources/local.conf` to match your environment:

- `ceph.s3.host`: The host name or inet address of the S3 gateway for CEPH
- `ceph.s3.accessKey`: The S3 access key (ID) for the CEPH service
- `ceph.s3.secretKey`: The S3 secret key for the CEPH service
- `ceph.s3.protocol`: Either `http` or `https`
- `aws.s3.accessKey`: The access key (ID) for Amazon S3
- `aws.s3.secretKey`: The secret key for Amazon S3

*#3* Make sure a Google Cloud credential is available as JSON in `src/test/resources/gcs-test.json`. Also edit `src/test/resources/local.conf` to set the project ID as `google.storage.projectId`.

*#4* Finally the test suite can be execute using SBT.

    sbt test

## S3 Client configuration

WSS3 have two differents `style`, `path` style and `virtualHost` style that represents how request URLs are created, you can specify which one to use during WSS3 instance creation :

```scala
import akka.stream.Materializer

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.s3._

def sample2(implicit m: Materializer): Unit = {
  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  // Creating a "path" style WSS3 :

  S3("accessKey", "secretKey", "http", "hostAndPort")
  // equivalent to
  S3(new java.net.URI("s3:http://accessKey:secretKey@hostAndPort/?style=path"))
  // and
  S3("s3:http://accessKey:secretKey@hostAndPort/?style=path")

  // Creating a "virtualHost" style WSS3 :

  S3.virtualHost("accessKey", "secretKey", "http", "hostAndPort")
  // equivalent to
  S3(new java.net.URI("s3:http://accessKey:secretKey@hostAndPort/?style=virtualHost"))
  // and
  S3("s3:http://accessKey:secretKey@hostAndPort/?style=virtualHost")

  ()
}
```

You can extends how WSS3 can be created using the `URIProvider` typeclass, see `S3.apply`.

## FAQ

When using with AWS, the style `virtualHost` is recommanded. Without (according your AWS settings), you can get the following error if using the `path` style.

    java.lang.NullPointerException: originalUrl
    at com.ning.http.client.uri.UriParser.parse(UriParser.java:X)

**Jet lag:** A S3 client must be configured with the appropriate system time. Otherwise with S3, the `RequestTimeTooSkewed` error can occur.

    java.lang.IllegalStateException: Could not update the contents of the object [...]. Response (403 / Forbidden): <?xml version="1.0" encoding="UTF-8"?><Error><Code>RequestTimeTooSkewed</Code></Error>

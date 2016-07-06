# S3 client

This library is a Scala client for object storage (e.g. S3/Amazon, S3/CEPH).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample).

    sbt test

**Requirements:**

- A JDK 1.8+ is required.
- [Play WS](https://www.playframework.com/documentation/latest/ScalaWS) must be provided; Tested with version 2.5.2.

## Usage

In your `build.sbt` (or the `project/Build.scala`):

```
libraryDependencies += "com.zengularity" %% "cabinet-s3" % "VERSION"

// If Play WS is not yet provided:
libraryDependencies += "com.typesafe.play" %% "play-ws" % "2.5.2"
```

Then, the S3 client can be used as following in your code.

```scala
import java.io._

import scala.concurrent.{ ExecutionContext, Future }

import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.{ FileIO, Sink, Source }

import play.api.libs.ws.WSClient

import com.zengularity.s3._

def sample1(implicit m: Materializer): Unit = {
  implicit def ec: ExecutionContext = m.executionContext

  // WSClient must be available in the implicit scope;
  // Here a default/standalone instance is declared
  implicit val ws: WSClient = com.zengularity.ws.WS.client()

  val s3: WSS3 = S3("accessKey", "secretKey", "http", "hostAndPort")
  // or S3.virtualHost(...) for S3 in virtual-host style

  val bucket = s3.bucket("aBucket")

  // Upload

  /* input */
  val file = new File("/path/to/local/file")
  lazy val data: Source[ByteString, _] = FileIO.fromFile(file)

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

## FAQ

When using with AWS, the style `virtual` is recommanded. Without (according your AWS settings), you can get the following error if using the `path` style.

    java.lang.NullPointerException: originalUrl
    at com.ning.http.client.uri.UriParser.parse(UriParser.java:X)

**Jet lag:** A S3 client must be configured with the appropriate system time. Otherwise with S3, the `RequestTimeTooSkewed` error can occur.

    java.lang.IllegalStateException: Could not update the contents of the object [...]. Response (403 / Forbidden): <?xml version="1.0" encoding="UTF-8"?><Error><Code>RequestTimeTooSkewed</Code></Error>

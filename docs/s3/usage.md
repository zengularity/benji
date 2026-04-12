# S3 Storage with Benji

This guide covers using Benji with Amazon S3 and S3-compatible services like CEPH and MinIO.

## Setup

Add the S3 module and Play WS to your `build.sbt`:

```ocaml
libraryDependencies += "com.zengularity" %% "benji-s3" % "{{site.latest_release}}"

// Play WS standalone
libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ahc-ws-standalone" % "2.2.6",
  "com.typesafe.play" %% "play-ws-standalone-xml" % "2.2.6"
)
```

## Basic usage

```scala
import java.nio.file.Paths

import scala.util.{ Failure, Success }

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
  // See "S3 Client configuration" section to see
  // how to create and configure a WSS3

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
      println(s"uploading ${chunk.size.toString} bytes")
      Future.successful(acc + chunk.size)
    }

  (data runWith upload).onComplete {
    case Failure(e) => println(s"Upload failed: ${e.getMessage}")
    case Success(_) => println("Upload ok")
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

## Testing

To run the compliance tests for this module, follow these steps.

**Step 1:** Copy the sample config:

```bash
cp src/test/resources/local.conf.sample src/test/resources/local.conf
```

**Step 2:** Edit `src/test/resources/local.conf` with your test environment details:

- `ceph.s3.host`, `ceph.s3.accessKey`, `ceph.s3.secretKey`, `ceph.s3.protocol` — for CEPH
- `aws.s3.accessKey`, `aws.s3.secretKey` — for Amazon S3
- `google.storage.projectId` — for Google Cloud Storage

**Step 3:** Provide Google Cloud credentials at `src/test/resources/gcs-test.json`

**Step 4:** Run tests via SBT:

```bash
sbt test
```

## S3 client configuration

Several factory methods create an S3 `ObjectStorage`, either with explicit parameters or using a configuration URI:

```scala
import akka.stream.Materializer

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.s3._

def sample2(implicit m: Materializer): Unit = {
  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()

  // Creating a "path" style WSS3:

  S3("accessKey", "secretKey", "httpProto", "hostAndPort")
  // equivalent to
  S3("s3:httpProto://accessKey:secretKey@hostAndPort/?style=path")

  // Creating a "virtualHost" style WSS3:

  S3.virtualHost("accessKey", "secretKey", "httpProto", "hostAndPort")
  // equivalent to
  S3("s3:httpProto://accessKey:secretKey@hostAndPort/?style=virtualHost")

  // Creating a "virtualHost" style WSS3 for AWS/V4:

  S3.virtualHostAwsV4(
    "accessKey", "secretKey", "httpProto", "hostAndPort", "region")
  // equivalent to
  S3("s3:httpProto://accessKey:secretKey@hostAndPort/?style=virtualHost&awsRegion=region")

  ()
}
```

The main settings are:

- **accessKey**: The S3 account identifier (e.g., AWS access key ID)
- **secretKey**: The secret associated with the access key for authentication
- **httpProto**: HTTP protocol: `http` or `https`
- **hostAndPort**: Server address and port (defaults based on protocol; e.g., `s3.amazonaws.com`)
- **style**: URL path style: `path` or [`virtualHost`](https://docs.aws.amazon.com/AmazonS3/latest/dev/VirtualHosting.html)
- **awsRegion**: AWS region for [signature V4](https://docs.aws.amazon.com/general/latest/gr/signature-version-4.html) (required when style is `virtualHost`)

> Both `accessKey` and `secretKey` must be provided as-is in URIs (not URI-encoded).

URI configuration format:

```
s3://${httpProto}://${accessKey}:${secretKey}@${hostAndPort}/?style=${style}
```

Optional query parameters:

- `requestTimeout` — Request timeout in milliseconds (e.g., `&requestTimeout=30000`)

## Troubleshooting

### NullPointerException with path-style URLs

When using AWS S3 with `path` style, you may see this error:

```
java.lang.NullPointerException: originalUrl
at com.ning.http.client.uri.UriParser.parse(UriParser.java:X)
```

Solution: Use `virtualHost` style instead (recommended for AWS).

### RequestTimeTooSkewed error

S3 requires accurate system time. This error indicates a time mismatch:

```
java.lang.IllegalStateException: Could not update the contents of the object [...]. 
Response (403 / Forbidden): <?xml version="1.0" encoding="UTF-8"?>
<Error><Code>RequestTimeTooSkewed</Code></Error>
```

Solution: Synchronize your system clock with an NTP server.

### Null version IDs on non-versioned buckets

When using versioning, non-versioned buckets return version ID as the string `"null"` (not `null`).

See [AWS S3 Versioning documentation](https://docs.aws.amazon.com/AmazonS3/latest/dev/manage-objects-versioned-bucket.html) for details.

### S3 bucket naming restrictions

Follow [AWS bucket naming rules](https://docs.aws.amazon.com/AmazonS3/latest/dev/BucketRestrictions.html):
- 3–63 characters
- Lowercase letters, numbers, and hyphens only
- DNS-compliant names recommended

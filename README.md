# S3 client

This library is a Scala client for S3-compiliant object storage (e.g. Amazon, CEPH).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt publish-local

**Run the tests:** The integration tests can be executed with SBT, after having configured one Amazon S3 account and one CEPH S3 account (in `src/test/resources/test.conf` file or in the associated `local.conf`).

    sbt test

## Usage

In your `project/Build.scala`:

```scala
libraryDependencies ++= Seq(
  "com.zengularity" %% "s3" % "VERSION"
)
```

Then, the S3 client can be used as following in your code.

```
import java.io._
import scala.concurrent.Future
import play.api.libs.iteratee.{ Enumerator, Iteratee }
import com.zengularity.s3._

// WSClient must be available in the implicit scope;
// Here a default/standalone instance is declared
implicit val ws = WS.client()

// As the S3 operations are async, needs an ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

val s3: WSS3 = S3("accessKey", "secretKey", "http", "hostAndPort")
// or S3.virtualHost(...) for S3 in virtual-host style

val bucket = s3.bucket("aBucket")

// Upload

/* input */
lazy val fileIn = new FileInputStream("/path/to/local/file")
lazy val data: Enumerator[Array[Byte]] = Enumerator.fromStream(fileIn)

/* target object */
val newObj = bucket.obj("newObject.ext")

/* declare the upload pipeline */
val upload: Iteratee[Array[Byte], Long] =
  newObj.put[Array[Byte], Long](0L) { (acc, chunk) =>
    println(s"uploading ${chunk.size} bytes")
    Future.successful(acc + chunk.size)
  }

(data |>>> upload).onComplete {
  case res => println(s"Upload result: $res")
}

// Take care to release the underlying resources
fileIn.close()
ws.close()
```
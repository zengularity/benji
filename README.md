# Object Storage framework

This library is a Scala framework for Object Storage (e.g. S3/Amazon, S3/CEPH, Google Cloud Storage).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt publishLocal

## Usage

According your Object Storage, the following modules are available.

- [S3](./s3/README.md) for Amazon (or compliant Object Storage, like CEPH).
- [Google Cloud Storage](./google/README.md).
- [Apache VFS](./vfs/README.md)

These modules can be configured as dependencies in your `build.sbt` (or `project/Build.scala`):

```
val benjiVer = "VERSION"

libraryDependencies += "com.zengularity" %% "benji-s3" % benjiVer

libraryDependencies += "com.zengularity" %% "benji-google" % benjiVer

// If Play WS is not yet provided:
libraryDependencies += "com.typesafe.play" %% "play-ws" % "2.5.4"
```

Then the storage operations can be called according the DSL from your `ObjectStorage` instance.

> Generally, these operations must be applied in a scope providing an `Materializer` and a transport instance (whose type is according the `ObjectStorage` instance; e.g. `play.api.libs.ws.WSClient` for S3).

### Bucket operations

The operations to manage the buckets are available on the `ObjectStorage` instance.

#### Listing the buckets

- `ObjectStorage.buckets(): Source[Bucket, NotUsed]` (note the final `()`); Can be processed using an `Sink[Bucket, _]`.
- `ObjectStorage.buckets.collect[M]: Future[M[Bucket]]` (when `CanBuildFrom[M[_], Bucket, M[Bucket]]`)

```scala
import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.StandaloneWSClient

import com.zengularity.benji.Bucket
import com.zengularity.benji.s3.WSS3
import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport }

def listBuckets(s3: WSS3)(implicit m: Materializer, tr: StandaloneWSClient): Future[List[Bucket]] = s3.buckets.collect[List]

def enumerateBucket(gcs: GoogleStorage)(implicit m: Materializer, tr: GoogleTransport): Source[Bucket, NotUsed] = gcs.buckets()
```

#### Resolve a bucket

In order to update a bucket, a `BucketRef` must be obtained (rather than the read-only metadata `Bucket`).

- `ObjectStorage.bucket(String): BucketRef`

```scala
import scala.concurrent.ExecutionContext

import com.zengularity.benji.{ BucketRef, ObjectStorage }

def obtainRef(storage: ObjectStorage, name: String)(implicit ec: ExecutionContext): BucketRef = storage.bucket(name)
```

### Object operations

The operations to manage the objects are available on the `ObjectStorage` instance.

#### Listing the objects

The objects can be listed from the parent `BucketRef`.

- `BucketRef.objects()` (note the final `()`); Can be processed using an `Sink[Object, _]`.
- `BucketRef.objects.collect[M[Object]]` (when `CanBuildFrom[M[_], Object, M[Object]]`)

```scala
import scala.collection.immutable.Set

import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.StandaloneWSClient

import com.zengularity.benji.{ BucketRef, Object }
import com.zengularity.benji.s3.WSS3BucketRef

def objectSet(bucket: WSS3BucketRef)(implicit m: Materializer, tr: StandaloneWSClient): Future[Set[Object]] = bucket.objects.collect[Set]

def enumerateObjects(bucket: BucketRef)(implicit m: Materializer): Source[Object, NotUsed] = bucket.objects()
```

#### Get an object

In order to manage an object, an `ObjectRef` must be obtained (rather than the read-only metadata `Object`).

- `BucketRef.obj(String): ObjectRef`

```scala
import scala.concurrent.ExecutionContext

import com.zengularity.benji.{ BucketRef, ObjectRef }

def obtainRef(bucket: BucketRef, name: String)(implicit ec: ExecutionContext): ObjectRef = bucket.obj(name)
```

#### Upload data

To upload data to a previously obtained `ObjectRef`, the `put` functions can be used.

- `ObjectRef.put[E : Writer]: Sink[E, NotUsed]`
- `ObjectRef.put[E : Writer](size: Long): Sink[E, NotUsed]`
- `ObjectRef.put[E : Writer, A](z: => A, threshold: Bytes, size: Option[Long])(f: (A, Chunk) => A): Sink[E, NotUsed]`

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import play.api.libs.ws.BodyWritable

import com.zengularity.benji.BucketRef

// Upload with any ObjectStorage instance
def upload(bucket: BucketRef, objName: String, data: => Source[Array[Byte], NotUsed])(implicit m: Materializer, w: BodyWritable[Array[Byte]]): Future[(String, Long)] = {
  implicit def ec: ExecutionContext = m.executionContext

  val storeObj = bucket.obj(objName)

  val to: Sink[Array[Byte], Future[Long]] =
    storeObj.put[Array[Byte], Long](0L) { (acc, chunk) =>
      println(s"uploading ${chunk.size} bytes of $objName @ $acc")
      Future.successful(acc + chunk.size)
    }

  (data runWith to).transform({ (size: Long) =>
    println(s"Object uploaded to ${bucket.name}/$objName (size = $size)")
    objName -> size
  }, { err =>
    println(s"fails to upload the object $objName", err)
    err
  })
}

import play.api.libs.ws.StandaloneWSClient

import com.zengularity.benji.s3.WSS3

def putToS3[A : BodyWritable](storage: WSS3, bucketName: String, objName: String, data: => Source[A, NotUsed])(implicit m: Materializer, tr: StandaloneWSClient): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  for {
    bucketRef <- { // get-or-create
      val ref = storage.bucket(bucketName)
      ref.create(failsIfExists = true).map(_ => ref)
    }
    storageObj = bucketRef.obj(objName)
    _ <- data runWith storageObj.put[A]
  } yield ()
}
```

### Datatypes

TODO

- Metadata types: `Bucket`, `Object`
- Working references: `BucketRef`, `ObjectRef`

## FAQ

**Bucket naming Restrictions:** In a multiple modules usages, it is recommended to follow DNS-compliant bucket naming, with the following additional restrictions:
 - Names length must be between 3 and 64 characters.
 - Names must contains only lower-cases characters, numbers, dots and hyphens.
 - Names must start and end with lower-cases characters and numbers.
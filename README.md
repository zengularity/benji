# Object Storage framework

This library is a Scala framework for Object Storage (e.g. S3/Amazon, S3/CEPH, Google Cloud Storage).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt publish-local

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

Then the storage operations can be called according the DSL from your `ObjectStorage[T]` instance.

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

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.Bucket
import com.zengularity.benji.s3.WSS3
import com.zengularity.benji.google.{ GoogleStorage, GoogleTransport }

def listBuckets(s3: WSS3)(implicit m: Materializer, tr: StandaloneAhcWSClient): Future[List[Bucket]] = s3.buckets.collect[List]

def enumerateBucket(gcs: GoogleStorage)(implicit m: Materializer, tr: GoogleTransport): Source[Bucket, NotUsed] = gcs.buckets()
```

#### Get a bucket

In order to update a bucket, a `BucketRef` must be obtained (rather than the read-only metadata `Bucket`).

- `ObjectStorage.bucket(String): BucketRef[_]`

```scala
import scala.concurrent.ExecutionContext

import com.zengularity.benji.{ BucketRef, ObjectStorage }
import com.zengularity.benji.google.{ GoogleBucketRef, GoogleStorage, GoogleTransport }

def googleBucket(gcs: GoogleStorage, name: String)(implicit ec: ExecutionContext, tr: GoogleTransport): GoogleBucketRef = gcs.bucket(name)

def obtainRef[T <: ObjectStorage[T]](storage: T, name: String)(implicit ec: ExecutionContext, tr: T#Pack#Transport): BucketRef[T] = storage.bucket(name)
// Generic - Works with any kind of ObjectStorage[T]
```

### Object operations

The operations to manage the objects are available on the `ObjectStorage` instance.

#### Listing the objects

The objects can be listed from the parent `BucketRef`.

- `BucketRef[T].objects()` (note the final `()`); Can be processed using an `Sink[Object, _]`.
- `BucketRef[T].objects.collect[M[Object]]` (when `CanBuildFrom[M[_], Object, M[Object]]`)

```scala
import scala.collection.immutable.Set

import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.{ BucketRef, Object, ObjectStorage }
import com.zengularity.benji.s3.WSS3BucketRef

def objectSet(bucket: WSS3BucketRef)(implicit m: Materializer, tr: StandaloneAhcWSClient): Future[Set[Object]] = bucket.objects.collect[Set]

def enumerateObjects[T <: ObjectStorage[T]](bucket: BucketRef[T])(implicit m: Materializer, tr: T#Pack#Transport): Source[Object, NotUsed] = bucket.objects()
// Generic - Works with any kind of ObjectStorage[T]
```

#### Get an object

In order to manage an object, an `ObjectRef` must be obtained (rather than the read-only metadata `Object`).

- `BucketRef[T].obj(String): ObjectRef[_]`

```scala
import scala.concurrent.ExecutionContext

import com.zengularity.benji.{ BucketRef, ObjectRef, ObjectStorage }
import com.zengularity.benji.google.{ GoogleBucketRef, GoogleObjectRef, GoogleTransport }

def obtainRef[T <: ObjectStorage[T]](bucket: BucketRef[T], name: String)(implicit ec: ExecutionContext, tr: T#Pack#Transport): ObjectRef[T] = bucket.obj(name)
// Generic - Works with any kind of ObjectStorage[T]

def googleObject(bucket: GoogleBucketRef, name: String)(implicit ec: ExecutionContext, tr: GoogleTransport): GoogleObjectRef = bucket.obj(name)
```

#### Upload data

To upload data to a previously obtained `ObjectRef`, the `put` functions can be used.

- `ObjectRef[T].put[E : Writer]: Sink[E, NotUsed]`
- `ObjectRef[T].put[E : Writer](size: Long): Sink[E, NotUsed]`
- `ObjectRef[T].put[E : Writer, A](z: => A, threshold: Bytes, size: Option[Long])(f: (A, Chunk) => A): Sink[E, NotUsed]`

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import com.zengularity.benji.{ BucketRef, ObjectStorage }

// Upload with any ObjectStorage instance
def upload[T <: ObjectStorage[T]](bucket: BucketRef[T], objName: String, data: => Source[Array[Byte], NotUsed])(implicit m: Materializer, tr: T#Pack#Transport, w: T#Pack#Writer[Array[Byte]]): Future[(String, Long)] = {
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

import play.api.libs.ws.ahc.StandaloneAhcWSClient

import com.zengularity.benji.s3.WSS3

def putToS3[A : WSS3#Pack#Writer](storage: WSS3, bucketName: String, objName: String, data: => Source[A, NotUsed])(implicit m: Materializer, tr: StandaloneAhcWSClient): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  for {
    bucketRef <- { // get-or-create
      val ref = storage.bucket(bucketName)
      ref.create(checkBefore = true).map(_ => ref)
    }
    storageObj = bucketRef.obj(objName)
    _ <- data runWith storageObj.put[A]
  } yield ()
}
```

### Datatypes

TODO

- Metadata types: `Bucket`, `Object`
- Working references: `BucketRef[T]`, `ObjectRef[T]`

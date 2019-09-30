---
layout: default
---

# QuickStart

This guide is how to easily start using Benji in your project.

*See also: [API](https://zengularity.github.io/benji/api/)*

## Bucket operations

According your Object Storage, the following modules are available.

- [S3](./s3/usage.md) for Amazon S3 (or compliant Object Storage, like CEPH).
- [Google Cloud Storage](./google/usage.md).
- [Apache VFS](./vfs/usage.md)

These modules can be configured as dependencies in your `build.sbt`.

```ocaml
val benjiVer = "{{site.latest_release}}"

libraryDependencies += "com.zengularity" %% "benji-s3" % benjiVer

libraryDependencies += "com.zengularity" %% "benji-google" % benjiVer

// If Play WS is not yet provided:
libraryDependencies += "com.typesafe.play" %% "play-ws-standalone" % "1.1.3"
```

Then the storage operations can be called according the DSL from your `ObjectStorage` instance.

> Generally, these operations must be applied in a scope providing an `Materializer` and a transport instance (whose type is according the `ObjectStorage` instance; e.g. `play.api.libs.ws.WSClient` for S3).

The Benji modules can also be easily integration with [Play Framework](https://www.playframework.com/); *See document about the [Play integration](play/integration.md)*.

### Listing the storage buckets

Several operations allow to list the buckets available in your storage. 

- `ObjectStorage.buckets(): Source[Bucket, NotUsed]` (note the final `()`); Can be processed reactively using an `Sink[Bucket, _]`.
- `ObjectStorage.buckets.collect[M]: Future[M[Bucket]]` (when `CanBuildFrom[M[_], Bucket, M[Bucket]]`)

These operations provided `Bucket`s, as metadata (not remote reference).

```scala
import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.Bucket
import com.zengularity.benji.s3.WSS3
import com.zengularity.benji.google.GoogleStorage

def listBuckets(s3: WSS3)(implicit m: Materializer): Future[List[Bucket]] =
  s3.buckets.collect[List]

def enumerateBucket(gcs: GoogleStorage)(implicit m: Materializer): Source[Bucket, NotUsed] = gcs.buckets()
```

### Resolve a bucket reference

In order to update a bucket, a `BucketRef` must be obtained (rather than the read-only metadata `Bucket`).

- `ObjectStorage.bucket(String): BucketRef`

```scala
import com.zengularity.benji.{ BucketRef, ObjectStorage }

def obtainRef(storage: ObjectStorage, name: String): BucketRef = storage.bucket(name)
```

## Object operations

The operations to manage the objects are available on the `ObjectStorage` instance, using `ObjectRef` (remote references).

### Listing the objects

The objects can be listed from the parent `BucketRef`.

- `BucketRef.objects()` (note the final `()`); Can be processed using an `Sink[Object, _]`.
- `BucketRef.objects.collect[M[Object]]` (when `CanBuildFrom[M[_], Object, M[Object]]`)

```scala
import scala.collection.immutable.Set

import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ BucketRef, Object }
import com.zengularity.benji.s3.WSS3BucketRef

def objectSet(bucket: WSS3BucketRef)(implicit m: Materializer): Future[Set[Object]] = bucket.objects.collect[Set]

def enumerateObjects(bucket: BucketRef)(implicit m: Materializer): Source[Object, NotUsed] = bucket.objects()
```

### Resolve an object reference

In order to manage an object, an `ObjectRef` must be obtained (rather than the read-only metadata `Object`).

- `BucketRef.obj(String): ObjectRef`

```scala
import com.zengularity.benji.{ BucketRef, ObjectRef }

def obtainRef(bucket: BucketRef, name: String): ObjectRef = bucket.obj(name)
```

### Upload data

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
      println(
        s"uploading ${chunk.size.toString} bytes of $objName @ ${acc.toString}")

      Future.successful(acc + chunk.size)
    }

  (data runWith to).transform({ (size: Long) =>
    println(
      s"Object uploaded to ${bucket.name}/$objName (size = ${size.toString})")

    objName -> size
  }, { err =>
    println(s"fails to upload the object $objName: ${err.getMessage}")
    err
  })
}

import com.zengularity.benji.s3.WSS3

def putToS3[A : BodyWritable](storage: WSS3, bucketName: String, objName: String, data: => Source[A, NotUsed])(implicit m: Materializer): Future[Unit] = {
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

## See also

- [Changelog](changelog.md)

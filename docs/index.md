---
layout: default
---

# QuickStart

This guide helps you get started with Benji in your project.

*See also: [API Documentation](https://zengularity.github.io/benji/api/)*

## Choosing your backend

Benji supports multiple object storage backends. Select the module that matches your storage provider:

- [S3](./s3/usage.md) — Amazon S3 and compatible services (CEPH, MinIO, etc.)
- [Google Cloud Storage](./google/usage.md) — Google Cloud Storage
- [Apache VFS](./vfs/usage.md) — Local and remote filesystems via Apache Commons VFS

## Adding Benji to your project

Add the desired storage module to your `build.sbt`:

```ocaml
val benjiVer = "{{site.latest_release}}"

// For S3
libraryDependencies += "com.zengularity" %% "benji-s3" % benjiVer

// For Google Cloud Storage
libraryDependencies += "com.zengularity" %% "benji-google" % benjiVer

// For VFS (filesystem)
libraryDependencies += "com.zengularity" %% "benji-vfs" % benjiVer

// If Play WS is not already in your dependencies:
libraryDependencies += "com.typesafe.play" %% "play-ws-standalone" % "2.2.6"
```

For Play Framework integration, see the [Play integration guide](play/integration.md).

## Core concepts

All storage operations are performed through an `ObjectStorage` instance, which provides a unified API regardless of backend. Operations require an implicit `Materializer` (for Akka Streams) and a transport instance (e.g., `WSClient` for S3/Google).

### Listing buckets

Retrieve all available buckets: 

- `ObjectStorage.buckets()` — Returns a reactive stream of buckets
- `ObjectStorage.buckets.collect[M]` — Collects all buckets into a collection (requires `CanBuildFrom`)

These operations return `Bucket` metadata (read-only).

```scala
import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.Bucket
import com.zengularity.benji.s3.WSS3
import com.zengularity.benji.google.GoogleStorage

def listBuckets(s3: WSS3)(implicit m: Materializer): Future[List[Bucket]] =
  s3.buckets.collect[List]()

def enumerateBucket(gcs: GoogleStorage)(implicit m: Materializer): Source[Bucket, NotUsed] = gcs.buckets()
```

### Getting a bucket reference

To create or delete a bucket, you need a `BucketRef` (mutable reference) rather than the read-only `Bucket` metadata:

- `ObjectStorage.bucket(String): BucketRef`

```scala
import com.zengularity.benji.{ BucketRef, ObjectStorage }

def obtainRef(storage: ObjectStorage, name: String): BucketRef = storage.bucket(name)
```

## Object operations

The operations to manage the objects are available on the `ObjectStorage` instance, using `ObjectRef` (remote references).

### Listing objects in a bucket

Get all objects from a `BucketRef`:

- `BucketRef.objects()` — Returns a reactive stream of objects
- `BucketRef.objects.collect[M]` — Collects all objects into a collection

```scala
import scala.collection.immutable.Set

import scala.concurrent.Future

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import com.zengularity.benji.{ BucketRef, Object }
import com.zengularity.benji.s3.WSS3BucketRef

def objectSet(bucket: WSS3BucketRef)(implicit m: Materializer): Future[Set[Object]] = bucket.objects.collect[Set]()

def enumerateObjects(bucket: BucketRef)(implicit m: Materializer): Source[Object, NotUsed] = bucket.objects()
```

### Getting an object reference

To read, write, or delete an object, you need an `ObjectRef` (mutable reference) rather than the read-only `Object` metadata:

- `BucketRef.obj(String): ObjectRef`

```scala
import com.zengularity.benji.{ BucketRef, ObjectRef }

def obtainRef(bucket: BucketRef, name: String): ObjectRef = bucket.obj(name)
```

### Uploading data

Write data to an object via the `put` functions:

- `ObjectRef.put[E : Writer]` — Streams data into an object
- `ObjectRef.put[E : Writer](size: Long)` — Streams with a known size hint
- `ObjectRef.put[E : Writer, A](z: => A, threshold: Bytes, size: Option[Long])(f: (A, Chunk) => A)` — Streams with custom accumulation logic

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import play.api.libs.ws.BodyWritable

import com.zengularity.benji.BucketRef

// Generic upload function
def upload(bucket: BucketRef, objName: String, data: Source[Array[Byte], NotUsed])(
  implicit m: Materializer, w: BodyWritable[Array[Byte]]
): Future[(String, Long)] = {
  implicit def ec: ExecutionContext = m.executionContext

  val storeObj = bucket.obj(objName)

  val to: Sink[Array[Byte], Future[Long]] =
    storeObj.put[Array[Byte], Long](0L) { (acc, chunk) =>
      println(s"uploading ${chunk.size} bytes of $objName (total: $acc)")
      Future.successful(acc + chunk.size)
    }

  (data runWith to).transform({ (size: Long) =>
    println(s"Object uploaded to ${bucket.name}/$objName (size = $size)")
    objName -> size
  }, { err =>
    println(s"Failed to upload object $objName: ${err.getMessage}")
    err
  })
}

// S3-specific upload with bucket creation
import com.zengularity.benji.s3.WSS3

def putToS3[A : BodyWritable](storage: WSS3, bucketName: String, objName: String, data: Source[A, NotUsed])(
  implicit m: Materializer
): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  for {
    bucketRef <- {
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

# MongoDB GridFS with Benji

This guide covers using Benji with [MongoDB GridFS](https://docs.mongodb.com/manual/core/gridfs/), which provides a MongoDB-based object storage backend for distributed file storage.

## Setup

Add the GridFS module to your `build.sbt`:

```ocaml
libraryDependencies += "com.zengularity" %% "benji-gridfs" % "{{site.latest_release}}"
```

## Basic usage

In MongoDB GridFS, databases are treated as storage instances, and collections are mapped to buckets:

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ FileIO, Sink, Source }

import akka.util.ByteString

import com.zengularity.benji.{ Bucket, Object }
import com.zengularity.benji.gridfs.GridFSStorage

def sample1(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  // Connect to MongoDB GridFS
  lazy val gridfs = GridFSStorage("gridfs:mongodb://localhost:27017/my-storage")

  val buckets: Future[List[Bucket]] = gridfs.buckets.collect[List]()

  buckets.flatMap {
    _.headOption.fold(Future.successful(println("No buckets found"))) { firstBucket =>
      val bucketRef = gridfs.bucket(firstBucket.name)
      val objects: Source[Object, NotUsed] = bucketRef.objects()
      
      objects.runWith(Sink.foreach[Object] { obj =>
        println(s"- ${obj.name}")
      }).map(_ => {})
    }
  }
}
```

## GridFS client configuration

Several factory methods create a GridFS `ObjectStorage`:

```scala
import scala.util.Try
import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.gridfs._

// Via configuration URI
def gridfs1: Try[ObjectStorage] =
  Try(GridFSStorage("gridfs:mongodb://localhost:27017/my-storage"))

// Via string URI directly
def gridfs2: ObjectStorage =
  GridFSStorage("gridfs:mongodb://localhost:27017/my-storage")

// Via factory helper
def gridfs3: ObjectStorage =
  GridFSFactory.create("gridfs:mongodb://localhost:27017/my-storage")
```

### URI configuration

URI format:

```
gridfs:mongodb://[host][:port]/[database]
```

**Parameters:**

- `host` — MongoDB server hostname (default: `localhost`)
- `port` — MongoDB server port (default: `27017`)
- `database` — Database name for storage (default: `benji`)

**Examples:**

```
gridfs:mongodb://localhost:27017/my-app
gridfs:mongodb://mongo-server:27017/production
gridfs:mongodb://127.0.0.1:27017/benji
```

## Upload and download

```scala
import java.nio.file.Paths

import scala.concurrent.{ ExecutionContext, Future }

import akka.stream.Materializer
import akka.stream.scaladsl.{ FileIO, Sink }

import com.zengularity.benji.gridfs.GridFSStorage

def uploadFile(implicit m: Materializer): Future[Long] = {
  implicit def ec: ExecutionContext = m.executionContext

  val gridfs = GridFSStorage("gridfs:mongodb://localhost:27017/my-storage")
  val bucket = gridfs.bucket("my-bucket")
  val obj = bucket.obj("my-file.txt")

  // Upload from file
  val path = Paths.get("/path/to/local/file.txt")
  val upload = FileIO.fromPath(path).runWith(obj.put[Long](0L) { (count, chunk) =>
    Future.successful(count + chunk.size)
  })

  upload
}

def downloadFile(implicit m: Materializer): Future[Long] = {
  implicit def ec: ExecutionContext = m.executionContext

  val gridfs = GridFSStorage("gridfs:mongodb://localhost:27017/my-storage")
  val bucket = gridfs.bucket("my-bucket")
  val obj = bucket.obj("my-file.txt")

  // Download to file
  val path = Paths.get("/path/to/destination.txt")
  val download = obj.get().runWith(FileIO.toPath(path))

  download.map(_.count)
}
```

## Bucket operations

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.stream.Materializer

import com.zengularity.benji.gridfs.GridFSStorage

def bucketOps(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  val gridfs = GridFSStorage("gridfs:mongodb://localhost:27017/my-storage")

  // List all buckets
  val allBuckets = gridfs.buckets.collect[List]()

  // Create a bucket
  val newBucket = gridfs.bucket("new-bucket").create()

  // Check bucket exists
  val bucketRef = gridfs.bucket("existing-bucket")
  val exists = bucketRef.exists()

  // Delete bucket
  val delete = bucketRef.delete()

  Future.sequence(Seq(newBucket, exists, delete)).map(_ => ())
}
```

## Testing

To run the compliance tests for this module locally, ensure MongoDB is running:

**Step 1:** Start MongoDB (local or Docker):

```bash
# Using Docker
docker run -d -p 27017:27017 mongo:8.0.3

# Or ensure local MongoDB is running
mongosh test
```

**Step 2:** Run the test suite:

```bash
sbt gridfs/test
```

The tests will connect to `mongodb://localhost:27017/benji-test` by default. To use a different MongoDB instance, set the `MONGODB_TEST_URI` environment variable:

```bash
export MONGODB_TEST_URI=gridfs:mongodb://mongo-server:27017/benji-test
sbt gridfs/test
```

## Production considerations

- **Authentication:** GridFS URIs support MongoDB connection strings with authentication credentials
- **Connection pooling:** ReactiveMongo handles connection pooling automatically
- **GridFS best practices:** See [MongoDB GridFS documentation](https://docs.mongodb.com/manual/core/gridfs/) for details on file storage limits and recommendations
- **Metadata:** Object metadata is stored alongside files in the MongoDB GridFS collections

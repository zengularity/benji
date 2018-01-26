# Benji VFS

Benji module for [Apache VFS](https://commons.apache.org/vfs/).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample) and `src/test/resources/gcs-test.json` files.

    sbt test

**Requirements:**

- A JDK 1.8+ is required.

## Usage

In your `build.sbt` (or `project/Build.scala`):

```
libraryDependencies += "com.zengularity" %% "benji-vfs" % "VERSION"
```

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import com.zengularity.benji.{ Bucket, Object }
import com.zengularity.benji.vfs.{ VFSBucketRef, VFSStorage, VFSTransport }

// Settings
val projectId = "vfs-project-123456"
val appName = "Foo"

def sample1(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext
  val vfsTransport = VFSTransport.temporary(projectId).get
  lazy val vfs = VFSStorage(vfsTransport)

  val buckets: Future[List[Bucket]] = vfs.buckets.collect[List]

  buckets.flatMap {
    _.headOption.fold(Future.successful(println("No found"))) { firstBucket =>
      val bucketRef: VFSBucketRef = vfs.bucket(firstBucket.name)
      val objects: Source[Object, NotUsed] = bucketRef.objects()
      
      objects.runWith(Sink.foreach[Object] { obj =>
        println(s"- ${obj.name}")
      }).map(_ => {})
    }
  }
}
```

Then, the VFS can be used as ObjectStorage in your code, considering directories as buckets and files and objects.

> In order to be compatible accross the various FS supported by VFS itself, it's recommanded not to nest sub-directory inside buckets.

## VFS client configuration

There are several factories to create a VFS `ObjectStorage` client, either passing parameters separately, or using a configuration URI.


```scala
import scala.util.Try
import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.vfs._

def vfs1: ObjectStorage = {
  import org.apache.commons.vfs2.{ FileSystemManager, VFS }
  def fsManager: FileSystemManager = VFS.getManager()
  def vfsTransport = VFSTransport(fsManager)

  VFSStorage(vfsTransport)
}

// Temporary
def vfs2: Try[ObjectStorage] =
  VFSTransport.temporary("foo").map { vfsTransport =>
    VFSStorage(vfsTransport)
  }

// Using configuration URI
def vfs3: Try[ObjectStorage] =
  VFSTransport("vfs:file:///home/someuser/somedir").map { vfsTransport =>
    VFSStorage(vfsTransport)
  }
```

> The `.temporary` factory is available for testing, using a local directory with cleanup.

The format for the configuration URIs is the following:

    vfs:${anyUriSupportedByVfs}

The [VFS documentation](https://commons.apache.org/proper/commons-vfs/filesystems.html) is available to check the form of `anyUriSupportedByVfs`.
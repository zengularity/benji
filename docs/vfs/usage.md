# Apache VFS with Benji

This guide covers using Benji with [Apache Commons VFS](https://commons.apache.org/vfs/), which supports local filesystems and various remote filesystem backends.

## Setup

Add the VFS module to your `build.sbt`:

```ocaml
libraryDependencies += "com.zengularity" %% "benji-vfs" % "{{site.latest_release}}"
```

## Basic usage

In VFS, directories are treated as buckets and files as objects:

```scala
import scala.concurrent.{ ExecutionContext, Future }

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

import com.zengularity.benji.{ Bucket, Object }
import com.zengularity.benji.vfs.{ VFSStorage, VFSTransport }

// Settings
val projectId = "vfs-project-123456"
val appName = "Foo"

def sample1(implicit m: Materializer): Future[Unit] = {
  implicit def ec: ExecutionContext = m.executionContext

  Future.fromTry(VFSTransport.temporary(projectId)).flatMap {
    (vfsTransport: VFSTransport) => 
      lazy val vfs = VFSStorage(vfsTransport)

      val buckets: Future[List[Bucket]] = vfs.buckets.collect[List]()

      buckets.flatMap {
        _.headOption.fold(Future.successful(println("No found"))) { firstBucket =>
          val bucketRef = vfs.bucket(firstBucket.name)
          val objects: Source[Object, NotUsed] = bucketRef.objects()
      
          objects.runWith(Sink.foreach[Object] { obj =>
            println(s"- ${obj.name}")
          }).map(_ => {})
        }
      }
    }
}
```


Then use VFS as an ObjectStorage in your code:

> For maximum compatibility across VFS filesystem implementations, avoid nesting subdirectories inside buckets.

## VFS client configuration

Several factory methods create a VFS `ObjectStorage`:


```scala
import scala.util.Try
import com.zengularity.benji.ObjectStorage
import com.zengularity.benji.vfs._

// Via FileSystemManager
def vfs1: ObjectStorage = {
  import org.apache.commons.vfs2.{ FileSystemManager, VFS }
  val fsManager: FileSystemManager = VFS.getManager()
  VFSStorage(VFSTransport(fsManager))
}

// Temporary directory (useful for testing)
def vfs2: Try[ObjectStorage] =
  VFSTransport.temporary("my-project").map(VFSStorage(_))

// Via configuration URI
def vfs3: Try[ObjectStorage] =
  VFSTransport("vfs:file:///tmp/my-storage").map(VFSStorage(_))
```

> The `.temporary` factory is available for testing purposes and automatically cleans up the temporary directory.

### URI configuration

URI format:

```
vfs://${anyUriSupportedByVfs}
```

See [Apache Commons VFS filesystem documentation](https://commons.apache.org/proper/commons-vfs/filesystems.html) for supported URI schemes (local files, SFTP, FTP, ZIP, HTTP, etc.).

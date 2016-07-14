# Cabinet VFS

Cabinet module for [Apache VFS](https://commons.apache.org/vfs/).

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
libraryDependencies += "com.zengularity" %% "cabinet-vfs" % "VERSION"
```

Then, the VFS can be used as ObjectStorage in your code, considering directories as buckets and files and objects.

> In order to be compatible accross the various FS supported by VFS itself, it's recommanded not to nest sub-directory inside buckets.

**Transport:**

The VFS transport can be inited with a [`FileSystemManager`](https://commons.apache.org/proper/commons-vfs/apidocs/org/apache/commons/vfs2/FileSystemManager.html).

A convenient factory is available for testing, to use a temporary directory as filesystem.

```scala
import com.zengularity.vfs.VFSTransport

implicit def vfsTransport = VFSTransport.temporary("/tmp/foo")
```

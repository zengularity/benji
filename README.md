# Object Storage framework

This library is a Scala framework for Object Storage (e.g. S3/Amazon, S3/CEPH, Google Cloud Storage).

## Build

The project is using [SBT](http://www.scala-sbt.org/), so to build it from sources the following command can be used.

    sbt publishLocal

[![CircleCI](https://circleci.com/gh/zengularity/benji.svg?style=svg)](https://circleci.com/gh/zengularity/benji)

## Setup

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

resolvers ++= Seq(
  "Entrepot Releases" at "https://raw.github.com/zengularity/entrepot/master/releases",
  "Entrepot Snapshots" at "https://raw.github.com/zengularity/entrepot/master/snapshots"
)
```

Then the storage operations can be called according the DSL from your `ObjectStorage` instance.

> Generally, these operations must be applied in a scope providing an `Materializer` and a transport instance (whose type is according the `ObjectStorage` instance; e.g. `play.api.libs.ws.WSClient` for S3).

## Usage

- [QuickStart](docs/quickstart.md)
- [API](https://zengularity.github.io/benji/)

## Release

To prepare a new release the following command must be used.

    sbt release

## Publish

To publish a snapshot or a release on [Zengularity Entrepot](https://github.com/zengularity/entrepot):

- set the environment variable `REPO_PATH`; e.g. `export REPO_PATH=/path/to/entrepot/snapshots/`
- run the command `sbt publish` .

Then in Entrepot, the changes must be commited and pushed.

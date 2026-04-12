# Benji

A Scala framework for unified object storage access, supporting Amazon S3, Google Cloud Storage, and local/remote filesystems via Apache VFS.

## Build

The project is using [SBT](http://www.scala-sbt.org/), so to build it from sources the following command can be used.

```bash
./project/build.sh
```

[![CircleCI](https://circleci.com/gh/zengularity/benji/tree/master.svg?style=svg)](https://circleci.com/gh/zengularity/benji/tree/master)
[![Maven](https://img.shields.io/maven-central/v/com.zengularity/benji-core_2.13.svg)](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22benji-core_2.13%22)
[![Javadocs](https://javadoc.io/badge/com.zengularity/benji-core_2.12.svg)](https://javadoc.io/doc/com.zengularity/benji-core_2.13)

> The environment variable `PLAY_VERSION` can be set to build the `play` module appropriately.

## Getting Started

The simplest way to get started is with the [QuickStart guide](https://zengularity.github.io/benji/). Core concepts:

- All storage operations work through an `ObjectStorage` interface
- Buckets are accessed via `BucketRef` (for modification)
- Objects are accessed via `ObjectRef` (for read/write/delete)
- Operations are async with Akka Streams

**Available backends:**

- [S3](docs/s3/usage.md) — Amazon S3 and compatible services (CEPH, MinIO, etc.)
- [Google Cloud Storage](docs/google/usage.md) — Google's cloud storage service
- [Apache VFS](docs/vfs/usage.md) — Local and remote filesystems

## Documentation

- [QuickStart](https://zengularity.github.io/benji/) — Introduction and core concepts
- [Examples](./examples) — Runnable example code
- [API Docs](https://zengularity.github.io/benji/api/) — Detailed API documentation
- [Play Integration](docs/play/integration.md) — Using Benji with Play Framework

## Release

To prepare a new release the following command must be used.

```bash
sbt release
```

## Publish release

To publish a release on Maven Central, use the following steps.

- Build artifacts: `./project/build.sh`
- Publish all modules: `./project/deploy.sh <version> <pgp-key>`
- Publish only play modules:

```bash
export SCALA_MODULES="play:benji-play"
./project/deploy.sh <version> <pgp-key>
```

- Go to https://oss.sonatype.org/#stagingRepositories and login with user allowed to publish on Maven central.

## Publish snapshot

Execute `./project/snapshot.sh`

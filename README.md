# Benji

This library is a Scala framework for Object Storage (e.g. S3/Amazon, S3/CEPH, Google Cloud Storage).

## Build

The project is using [SBT](http://www.scala-sbt.org/), so to build it from sources the following command can be used.

    ./project/build.sh

[![CircleCI](https://circleci.com/gh/zengularity/benji/tree/master.svg?style=svg)](https://circleci.com/gh/zengularity/benji/tree/master)
[![Maven](https://img.shields.io/maven-central/v/com.zengularity/benji-core_2.13.svg)](http://search.maven.org/#search%7Cga%7C1%7Ca%3A%22benji-core_2.13%22)
[![Javadocs](https://javadoc.io/badge/com.zengularity/benji-core_2.12.svg)](https://javadoc.io/doc/com.zengularity/benji-core_2.13)

> The environment variable `PLAY_VERSION` can be set to build the `play` module appropriately.

## Setup

The operations to manage the buckets are available on the `ObjectStorage` instance, using `BucketRef` (bucket remote reference).

*See also: [Setup](docs/index.md#setup)*

## Usage

- [QuickStart](https://zengularity.github.io/benji/)
- [Examples](./examples)
- [API](https://zengularity.github.io/benji/api/)

## Release

To prepare a new release the following command must be used.

    sbt release

## Publish release

To publish a release on Maven Central, use the following steps.

- Build artifacts: `./project/build.sh`
- Publish all modules: `./project/deploy.sh <version> <pgp-key>`
- Publish only play modules:

```
export SCALA_MODULES="play:benji-play"
./project/deploy.sh <version> <pgp-key>
```

- Go to https://oss.sonatype.org/#stagingRepositories and login with user allowed to publish on Maven central.

## Publish snapshot

Execute `./project/snapshot.sh`

# Benji

This library is a Scala framework for Object Storage (e.g. S3/Amazon, S3/CEPH, Google Cloud Storage).

## Build

The project is using [SBT](http://www.scala-sbt.org/), so to build it from sources the following command can be used.

    ./project/build.sh

[![CircleCI](https://circleci.com/gh/zengularity/benji.svg?style=svg)](https://circleci.com/gh/zengularity/benji) 
[![Zen Entrepot](http://zen-entrepot.nestincloud.io/entrepot/shields/releases/com/zengularity/benji-core_2.12.svg)](https://zen-entrepot.nestincloud.io/entrepot/pom/releases/com/zengularity/benji-core_2.12)

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

## Publish

To publish a snapshot or a release on [Zengularity Entrepot](https://github.com/zengularity/entrepot):

- set the environment variable `REPO_PATH`; e.g. `export REPO_PATH=/path/to/entrepot/snapshots/`
- run the command `sbt publish` .

Then in Entrepot, the changes must be commited and pushed.

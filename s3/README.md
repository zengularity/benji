# Benji S3

This library is a Scala client for object storage (e.g. S3/Amazon, S3/CEPH).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample).

    sbt test

**Requirements:**

- A JDK 1.8+ is required.
- [Play Standalone WS](https://github.com/playframework/play-ws) must be provided; Tested with version 1.1.3.

## Usage

*See [S3 usage](../docs/s3/usage.md)*
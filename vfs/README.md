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

*See [VFS usage](../docs/vfs/usage.md)*
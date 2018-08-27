# Benji Google

Benji module for Google Cloud Storage.

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

**Run the tests:** The integration tests can be executed with SBT, after having configured the required account with the appropriate [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample) and `src/test/resources/gcs-test.json` files.

    sbt test

**Requirements:**

- A JDK 1.8+ is required.
- [Play Standalone WS](https://github.com/playframework/play-ws) must be provided; Tested with version 1.1.3.

## Usage

*See [Google usage](../docs/google/usage.md)*
# S3 client

This library is a Scala client for S3-compiliant object storage (e.g. Amazon, CEPH).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt publish-local

**Run the tests:** The integration tests can be executed with SBT, after having configured one Amazon S3 account and one CEPH S3 account (in `src/test/resources/test.conf` file or in the associated `local.conf`).

    sbt test

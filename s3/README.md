# Benji S3

This library is a Scala client for object storage (e.g. S3/Amazon, S3/CEPH).

## Build

The project is using [SBT](http://www.scala-sbt.org/).

    sbt compile

## Run the tests

Integration tests can be executed with SBT in two ways:

### Option 1: With MinIO (Local Development - Recommended)

This is the default configuration using a local MinIO simulator with automatic bucket initialization.

**Prerequisites:**
- Docker and Docker Compose must be installed

**Steps:**

1. Start MinIO with automatic bucket initialization:

    ```bash
    cd s3
    docker-compose up -d
    ```
    
    This automatically:
    - Starts the MinIO service on `localhost:9000`
    - Creates test buckets: `benji-test`, `benji-test-versioning`, `benji-test-ceph`
    - Sets up the S3-compatible storage interface

2. Run tests (uses default MinIO configuration from `src/test/resources/tests.conf`):

    ```bash
    cd ..
    sbt test
    ```
    
    Or run specific S3 test suites:
    
    ```bash
    sbt "s3/testOnly *S3AwsSpec"
    sbt "s3/testOnly *S3FactorySpec"
    ```

3. Stop MinIO when done:

    ```bash
    cd s3
    docker-compose down
    ```

### Option 2: With Real AWS/Ceph Accounts

Configure the required account credentials in [`src/test/resources/local.conf`](./src/test/resources/local.conf.sample):

```bash
cp src/test/resources/local.conf.sample src/test/resources/local.conf
# Edit local.conf with your AWS or Ceph credentials
sbt test
```

**Requirements:**

- A JDK 1.8+ is required.
- [Play Standalone WS](https://github.com/playframework/play-ws) must be provided; Tested with version 1.1.3.
- Docker and Docker Compose (for MinIO option)

## MinIO Configuration Details

MinIO provides S3-compatible storage for both AWS and Ceph test coverage:

- **Default credentials:** `minioadmin` / `minioadmin123`
- **Default endpoint:** `http://localhost:9000`
- **Console:** `http://localhost:9001` (for web UI)
- **API compatibility:** S3-compatible (covers both AWS and Ceph S3 API variants)
- **Test buckets:** Automatically created on container startup

The `tests.conf` file includes default MinIO settings and can be overridden by creating a `local.conf` file.

## Architecture

The Docker Compose setup includes:
- **minio**: S3-compatible object storage service
- **minio-init**: Initialization service that creates required buckets after MinIO is healthy

## Usage

*See [S3 usage](../docs/s3/usage.md)*
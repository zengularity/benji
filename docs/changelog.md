# Changelog

## Release 2.2.0

Support Scala 3 (still minimal for Play modules)

## Release 2.1.0

- Update dependencies (#94):

```
// For Benji Google
//Remove dependency on: com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.auth.oauth2.GoogleCredentials
```

## Release 2.0.5

- Various dependency updates

## Release 2.0.4

- Support Scala 2.13 (#50)
- Various dependency updates (#49)

## Release 2.0.3

- Support Play 2.7 (#26): Version for Benji Play module is now suffixes with Play major (e.g. `2.0.3-play26`)
- Backward compatibility for Scala 2.11 (+ various dependency & build updates) (#25)
- Fix S3 copy encoding (#23)
- Support prefix filter when listing objects of a bucket (#24)

## Release 2.0.2

- Fix VFS resource management.
- Setup MiMa to enforce API compatibility.

## Release 2.0.1

- Support AWS signature V4

## Release 2.0.0

- Rename the project to Benji, with module specifications as `"com.zengularity.benji" %% "benji-x" % version`.
- Upgrade to Scala 2.12, Akka Stream 2.5.4 and Play WS (standalone) 1.1.3.
- Easy storage configuration:
    - `S3("s3:http://accessKey:secretKey@hostAndPort/?style=path")`
- The delete operation now support a `recursive` option to be able to remove a bucket which still contains some object (by deleting them first); The delete behaviour has also been unified accross the modules (e.g. error handling in case the bucket to delete doesn't exist).

## Release 1.3.3

- Upgrade to Akka Stream 2.4.10 (with the provided `Flow.foldAsync`).
- Apply the Akka Stream TestKit in the tests.

## Release 1.3.1

New VFS module

## Release 1.3.0

Akka Stream migration (see Play [Migration guide](https://www.playframework.com/documentation/2.5.x/StreamsMigration25)).

## Release 1.2.x

The `Bucket`, `Object` and `Bytes` case classes are moved to the package `com.zengularity.storage`.

In the case class `Bucket`, the property `creationDate` is renamed to `creationTime`.

In the case class `Object`:

- the property `key` is renamed to `name`,
- the property `bytes` is renamed to `size`, and its type is updated to `Bytes`.

In the `Bytes` value class, the property `inBytes` is renamed to `bytes`.

In the `WSS3` class;

- The function `bucket(String)` now returns a value implementing the new `BucketRef` interface.
- The function `obj(String)` returns a value implementing the new `ObjectRef` interface.

The new `ObjectRef` interface;

- The property `bucketName` is renamed to `bucket`.
- The property `objectName` is renamed to `name`.
- The `.get` function now returns a `GetRequest`, that can be applied with some optional arguments (e.g. ContentRange).
- The `.put[E, A]` function now returns a `PutRequest`, that can be applied with some optional arguments.
- The behaviour of the `.delete` function is now specified in case the referenced object doesn't exist: it returns a failure
.

The `WSS3ObjectRef` class is now an implementation of the generic `ObjectRef`.

- The property `defaultMaxPart` is moved to the S3 `RESTPutRequest`, where it can be adjusted using `.withMaxPart`.

The new `BucketRef` interface;

- The `.objects` function now returns a `ListRequest` that can be applied with options, and must be called using `.objects()` (with `()`). The `ListRequest` also provides a convenient `.collect` function.

The new `ObjectStorage` trait implemented by WSS3;

- The `.buckets` function now returns a `BucketsRequest`, that can be applied.

The `WSRequestBuilder` has been refactored as a sealed trait. The related `PreparedRequest` case class is removed.

[SLF4J](http://slf4j.org/) is directly used for logging, instead of `play.api.Logger`.

The utility `Iteratees` is moved as `com.zengularity.storage.Streams`, with the new `consumeAtMost` function (used for the Google module).

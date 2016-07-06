# Changelog

## Release 1.2.2

Concerning the `ObjectRef` API;

- The behaviour of the `.delete` function is now specified in case the referenced object doesn't exist: it fails.
- A new `.moveTo` function is available.

## Release 1.2.0

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

The `WSS3ObjectRef` class is now an implementation of the generic `ObjectRef`, provided by the `cabinet-s3` module.

- The property `defaultMaxPart` is moved to the S3 `RESTPutRequest`, where it can be adjusted using `.withMaxPart`.

The new `BucketRef` interface;

- The `.objects` function now returns a `ListRequest` that can be applied with options, and must be called using `.objects()` (with `()`). The `ListRequest` also provides a convenient `.collect` function.

The new `ObjectStorage` trait implemented by WSS3;

- The `.buckets` function now returns a `BucketsRequest`, that can be applied.

The `WSRequestBuilder` has been refactored as a sealed trait. The related `PreparedRequest` case class is removed.

[SLF4J](http://slf4j.org/) is directly used for logging, instead of `play.api.Logger`.

The utility `Iteratees` is moved as `com.zengularity.storage.Streams`, with the new `consumeAtMost` function (used for the Google module).
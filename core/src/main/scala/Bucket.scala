/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

import java.time.LocalDateTime

/**
 * Metadata about a storage bucket.
 *
 * @param name the name of the bucket
 * @param creationTime the time of the bucket has been created
 *
 * @see [[BucketRef]]
 */
case class Bucket(name: String, creationTime: LocalDateTime)

/**
 * Metadata about a storage object.
 *
 * @param name the name of the object
 * @param size the binary size of the object
 * @param lastModifiedAt the time of the last modification for this object
 *
 * @see [[ObjectRef]]
 */
case class Object(name: String, size: Bytes, lastModifiedAt: LocalDateTime)

/**
 * Metadata about a versioned object.
 *
 * @param name the name of the object
 * @param size the binary size of the object or 0 when it's a delete marker
 * @param versionCreatedAt the time when this version was created
 * @param versionId the id of the version
 * @param isLatest indicates whether this version is the current version of the object or not.
 *
 * @see [[VersionedObjectRef]]
 */
case class VersionedObject(
    name: String,
    size: Bytes,
    versionCreatedAt: LocalDateTime,
    versionId: String,
    isLatest: Boolean)

/**
 * An explicit range of bytes.
 *
 * @param start the inclusive offset for the range start (< end)
 * @param end the inclusive index of the last byte of the range (> start)
 */
case class ByteRange(start: Long, end: Long)

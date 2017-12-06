package com.zengularity.benji

import scala.language.higherKinds

import java.time.LocalDateTime

/**
 * @param name the name of the bucket
 * @param creationTime the time of the bucket has been created
 */
case class Bucket(name: String, creationTime: LocalDateTime)

/**
 * @param name the name of the object
 * @param size the binary size of the object
 * @param lastModifiedAt the time of the last modification for this object
 */
case class Object(name: String, size: Bytes, lastModifiedAt: LocalDateTime)

/**
 * A transport pack.
 */
trait StoragePack {
  /** The transport type. */
  type Transport

  /** The type of writer, to send data using the transport. */
  type Writer[T]
}

/**
 * An explicit range of bytes.
 *
 * @param start the inclusive offset for the range start (< end)
 * @param end the inclusive index of the last byte of the range (> start)
 */
case class ByteRange(start: Long, end: Long)

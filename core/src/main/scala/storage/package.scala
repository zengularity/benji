package com.zengularity

import org.joda.time.DateTime

package object storage {
  /**
   * @param name the name of the bucket
   * @param creationTime the time of the bucket has been created
   */
  case class Bucket(name: String, creationTime: DateTime)

  /**
   * @param key the name of the object
   * @param bytes the binary size of the object
   * @param lastModifiedAt the time of the last modification for this object
   */
  case class Object(name: String, size: Bytes, lastModifiedAt: DateTime)

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
}

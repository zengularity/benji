package com.zengularity.s3

/**
 * Use this type when you want to specify a size
 * in terms of bytes or something like that in a type-safe way.
 */
case class Bytes(inBytes: Long) extends AnyVal {
  /**
   * Indicates whether this size value was exceeded by the given byte array.
   *
   * For example,
   *
   * {{
   * Bytes(5).wasExceeded("123456".getBytes)
   * }}
   *
   * @return true as that array has a length of 6 and is thus greater than what we indicated (5 bytes).
   */
  def wasExceeded(bytes: Array[Byte]): Boolean = inBytes <= bytes.length

  /**
   * Subtracts the size of the given array from this size indication, possibly returning negative values.
   */
  def -(bytes: Array[Byte]): Bytes = Bytes(inBytes - bytes.length)

  /** To compare sizes */
  def >(other: Bytes): Boolean = inBytes > other.inBytes
  def >=(other: Bytes): Boolean = inBytes >= other.inBytes
  def <(other: Bytes): Boolean = inBytes < other.inBytes
  def <=(other: Bytes): Boolean = inBytes <= other.inBytes
}

/** Bytes companion object */
object Bytes {
  val zero = Bytes(0)

  def kilobytes(i: Int): Bytes = Bytes(i * 1024)
  def megabytes(i: Int): Bytes = Bytes(i * 1024 * 1024)
}

/*
 * Copyright (C) 2018-2019 Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>
 */

package com.zengularity.benji

/**
 * Use this value type when you want to specify a size
 * in terms of bytes or something like that in a type-safe way.
 *
 * @param bytes the number of bytes (binary size)
 */
final class Bytes private (val bytes: Long) extends AnyVal {

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
  def wasExceeded(data: Array[Byte]): Boolean = bytes <= data.length

  /**
   * Subtracts the size of the given data from this size representation,
   * possibly returning negative values.
   */
  def -(data: Array[Byte]): Bytes = new Bytes(bytes - data.length)

  def -(other: Long): Bytes = new Bytes(bytes - other)

  /** To compare sizes */
  def >(other: Bytes): Boolean = bytes > other.bytes
  def >=(other: Bytes): Boolean = bytes >= other.bytes
  def <(other: Bytes): Boolean = bytes < other.bytes
  def <=(other: Bytes): Boolean = bytes <= other.bytes

  def /:(other: Long): Long = other / bytes

  override def toString = s"${bytes.toString}B"
}

/** Bytes companion object */
object Bytes {
  val MB: Long = 1024L * 1024L
  val zero: Bytes = new Bytes(0L)

  def apply(bytes: Long): Bytes = new Bytes(bytes)
  def kilobytes(i: Int): Bytes = new Bytes(i * 1024L)
  def megabytes(i: Int): Bytes = new Bytes(i * MB)
}

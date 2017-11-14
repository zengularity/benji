package com.zengularity.storage

import akka.util.ByteString

/** Chunk data and state representation. */
sealed trait Chunk {
  /** The binary data */
  def data: ByteString

  /** Alias for `data.size`. */
  @inline def size: Int = data.size

  override lazy val hashCode: Int =
    getClass.hashCode * data.hashCode
}

object Chunk {
  /** Returns a non-empty chunk (not in final position). */
  def apply(data: ByteString): Chunk = new NonEmpty(data)

  /** Returns a last chunk with some remaining data. */
  def last(remaining: ByteString): Last = new Last(remaining)

  /** A non-last chunk with some data. */
  final class NonEmpty(val data: ByteString) extends Chunk {
    override lazy val toString = s"NonEmpty(${data.size})"

    override def equals(that: Any): Boolean = that match {
      case NonEmpty(other) => data.equals(other)
      case _ => false
    }
  }

  /** Companion and extractors. */
  object NonEmpty {
    def unapply(that: Any): Option[ByteString] = that match {
      case c: NonEmpty => Some(c.data)
      case _ => None
    }
  }

  /** A last chunk, possibly with some data (or not). */
  final class Last(val data: ByteString) extends Chunk {
    override lazy val toString = s"Last(${data.size})"

    override def equals(that: Any): Boolean = that match {
      case Last(bytes) => data.equals(bytes)
      case _ => false
    }
  }

  /** Companion and extractors. */
  object Last {
    def unapply(that: Any): Option[ByteString] = that match {
      case last: Last => Some(last.data)
      case _ => None
    }
  }
}

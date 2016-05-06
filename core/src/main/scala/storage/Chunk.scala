package com.zengularity.storage

/** Chunk data and state representation. */
sealed trait Chunk {
  /** The binary data */
  def data: Option[Array[Byte]]

  override def equals(that: Any): Boolean = that match {
    case c: Chunk => c.data match {
      case Some(d) if data.exists(_.toList == d.toList) =>
        true

      case None if !data.isDefined =>
        true

      case _ => false
    }
    case _ => false
  }

  override lazy val hashCode: Int =
    getClass.hashCode * data.fold(-1)(_.toList.hashCode)
}

object Chunk {
  /** Returns a non-empty chunk (not in final position). */
  def apply(data: Array[Byte]): Chunk = new NonEmpty(data)

  /** Returns a last chunk, without data. */
  val last: Last = new Last(None)

  /** Returns a last chunk with some remaining data. */
  def last(remaining: Array[Byte]): Last = new Last(Some(remaining))

  /** A non-last chunk with some data. */
  final class NonEmpty(val bytes: Array[Byte]) extends Chunk {
    val data = Some(bytes)

    override lazy val toString = s"NonEmpty(${bytes.size})"
  }

  /** Companion and extractors. */
  object NonEmpty {
    def unapply(that: Any): Option[Array[Byte]] = that match {
      case c: NonEmpty => Some(c.bytes)
      case _           => None
    }
  }

  /** A last chunk, possibly with some data (or not). */
  final class Last(val data: Option[Array[Byte]]) extends Chunk {
    override lazy val toString = s"Last(${data.map(_.size)})"
  }

  /** Companion and extractors. */
  object Last {
    def unapply(that: Any): Option[Option[Array[Byte]]] = that match {
      case last: Last => Some(last.data)
      case _          => None
    }
  }
}

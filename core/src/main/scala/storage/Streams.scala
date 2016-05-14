package com.zengularity.storage

import scala.collection.mutable.ArrayBuilder

import scala.concurrent.ExecutionContext

import play.api.libs.iteratee.{ Cont, Done, Input, Iteratee }

/** Stream management utility. */
object Streams {

  /**
   * Creates an Iteratee that consumes byte arrays up until the buffer reaches a certain size. So, for example, if you
   * want to consume 512 bytes, and feed it 256 bytes at first, it will keep on consuming more until you feed another
   * 256 bytes. Only then will it go into the Done state using the total 512 bytes, appended together.
   *
   */
  def consumeAtLeast(size: Bytes): Iteratee[Array[Byte], Array[Byte]] =
    consumeAtLeast(size, size, Nil)

  private def consumeAtLeast(initial: Bytes, remaining: Bytes, chunks: List[Array[Byte]]): Iteratee[Array[Byte], Array[Byte]] = {
    // Are we still required to consume something?
    if (remaining > Bytes.zero) {
      Cont {
        case Input.Empty => {
          // In this case, nothing changes at all,
          // continue in the current state ..
          consumeAtLeast(initial, remaining, chunks)
        }

        case Input.El(bytes) => {
          // Include the input and recursively call this function again, which
          // then determines whether or not we'll continue consuming input.
          consumeAtLeast(initial, remaining - bytes, bytes :: chunks)
        }

        // If there's nothing else left, then just force the Done step by recursively calling this with a 0 size
        case Input.EOF => consumeAtLeast(initial, Bytes.zero, chunks)
      }
    } else {
      // Only now do we merge so that the final byte array has to be allocated only once, we don't copy
      // stuff all the time, etc - and every iteratee instance along the way remains referentially transparent
      // (i.e. we're not passing a mutable ArrayBuilder around, for example .. that would screw up things).
      val builder = ArrayBuilder.make[Byte]()
      builder.sizeHint(initial.bytes.toInt)
      // We always prepended elements, so now we need to reverse the whole list first
      chunks.reverseIterator foreach { chunk =>
        builder ++= chunk
      }
      Done(builder.result())
    }
  }

  /**
   * Returns an iteratee consuming at most the specified size.
   *
   * @param size the maximum number of bytes to be read from the enumerator
   */
  def consumeAtMost(size: Bytes)(implicit ec: ExecutionContext): Iteratee[Array[Byte], Chunk] = consumeAtMost(size, size, Nil)

  private def consumeAtMost(max: Bytes, remaining: Bytes, chunks: List[Array[Byte]], end: Boolean = false)(implicit ec: ExecutionContext): Iteratee[Array[Byte], Chunk] = {
    if (remaining > Bytes.zero) {
      Iteratee.head[Array[Byte]].flatMap {
        case Some(bytes) => {
          // Include the input and recursively call this function again, which
          // then determines whether or not we'll continue consuming input.
          consumeAtMost(max, remaining - bytes, bytes :: chunks, end)
        }

        case _ => {
          // If there's nothing else left then, just force the Done step bellow
          consumeAtMost(max, Bytes.zero, chunks, true)
        }
      }
    } else if (chunks.isEmpty) {
      Done(Chunk.last)
    } else {
      // Only now do we merge so that the final byte array has to be allocated only once, we don't copy
      // stuff all the time, etc - and every iteratee instance along the way remains referentially transparent
      // (i.e. we're not passing a mutable ArrayBuilder around, for example .. that would screw up things).

      val (merge, extra) = mergeAtMost(
        max.bytes.toInt, chunks.reverseIterator,
        0, ArrayBuilder.make[Byte], ArrayBuilder.make[Byte]
      )

      val ck = {
        if (!end || !extra.isEmpty) Chunk(merge)
        else if (merge.isEmpty) Chunk.last
        else Chunk.last(merge)
      }

      Done(ck, Input.El(extra))
    }
  }

  @annotation.tailrec
  private def mergeAtMost(maxSz: Int, chunks: Iterator[Array[Byte]], count: Int, merge: ArrayBuilder[Byte], extra: ArrayBuilder[Byte]): (Array[Byte], Array[Byte]) = {
    if (chunks.hasNext) {
      val chunk = chunks.next()
      val remaining = maxSz - count

      if (remaining > 0) {
        val (bytes, x) = {
          if (chunk.size <= remaining) chunk -> Array.empty[Byte]
          else chunk.splitAt(remaining)
        }

        mergeAtMost(maxSz, chunks, count + bytes.size,
          merge ++= bytes, extra ++= x)

      } else {
        // Keep the extra bytes
        mergeAtMost(maxSz, chunks, count, merge, extra ++= chunk)
      }
    } else merge.result() -> extra.result()
  }
}

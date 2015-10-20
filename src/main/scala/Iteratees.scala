package com.zengularity.s3

import play.api.libs.iteratee._

object Iteratees {

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
        // In this case, nothing changes at all, continue in the current state ..
        case Input.Empty     => consumeAtLeast(initial, remaining, chunks)
        // Include the input and recursively call this function again, which
        // then determines whether or not we'll continue consuming input.
        case Input.El(bytes) => consumeAtLeast(initial, remaining - bytes, bytes :: chunks)
        // If there's nothing else left, then just force the Done step by recursively calling this with a 0 size
        case Input.EOF       => consumeAtLeast(initial, Bytes.zero, chunks)
      }
    } else {
      // Only now do we merge so that the final byte array has to be allocated only once, we don't copy
      // stuff all the time, etc - and every iteratee instance along the way remains referentially transparent
      // (i.e. we're not passing a mutable ArrayBuilder around, for example .. that would screw up things).
      val builder = scala.collection.mutable.ArrayBuilder.make[Byte]()
      builder.sizeHint(initial.inBytes.toInt)
      // We always prepended elements, so now we need to reverse the whole list first
      chunks.reverseIterator foreach { chunk =>
        builder ++= chunk
      }
      Done(builder.result())
    }
  }
}

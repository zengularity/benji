package tests

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ Duration, FiniteDuration }

import play.api.libs.iteratee.{ Enumeratee, Enumerator, Iteratee }

import com.zengularity.storage.{ Chunk, Bytes, Streams }, Streams.{ consumeAtLeast, consumeAtMost }, Chunk.{ Last, NonEmpty }

class StreamSpec extends org.specs2.mutable.Specification {
  "Streams" title

  val timeout = Duration("5s").asInstanceOf[FiniteDuration]

  "Consumer" should {
    "try to take at least up to 5 bytes" >> {
      val consumer5 = consumeAtLeast(Bytes(5))

      "from an empty source" in { implicit ee: EE =>
        withEnumerator() {
          _ |>>> consumer5 must beEqualTo(Array.empty[Byte]).await(1, timeout)
        }
      }

      "from a single part of less than 5 bytes" in { implicit ee: EE =>
        withEnumerator(1, 3) {
          _ |>>> consumer5 must beEqualTo(Array[Byte](1, 1, 1)).
            await(1, timeout)
        }
      }

      "from a single part of exactly 5 bytes" in { implicit ee: EE =>
        withEnumerator(1, 5) {
          _ |>>> consumer5 must beEqualTo(Array[Byte](1, 1, 1, 1, 1)).
            await(1, timeout)
        }
      }

      "from a single part of more than 5 bytes" in { implicit ee: EE =>
        withEnumerator(1, 7) {
          _ |>>> consumer5 must beEqualTo(Array[Byte](1, 1, 1, 1, 1, 1, 1)).
            await(1, timeout)
        }
      }

      "from a 1st part of 5B and the 2nd of 3B" in { implicit ee: EE =>
        withEnumerator(1, 5) { part1 =>
          withEnumerator(1, 3, 2) { part2 =>
            val source = part1 >>> part2
            def take1 = source |>>> consumer5
            def take2 = source |>>> {
              Enumeratee.grouped(consumer5) &>> Iteratee.getChunks
            }

            take1 aka "take first" must beEqualTo(Array[Byte](1, 1, 1, 1, 1)).
              await(1, timeout) and (take2.map(_.map(_.toList)).
                aka("take all") must beEqualTo(List(
                  List[Byte](1, 1, 1, 1, 1), List[Byte](2, 2, 2)
                )).await(1, timeout))
          }
        }
      }

      "from a 1st part of 3B and the 2nd of 5B" in { implicit ee: EE =>
        withEnumerator(1, 3) { part1 =>
          withEnumerator(1, 5, 2) { part2 =>
            val source = part1 >>> part2
            def take = source |>>> consumer5

            take must beEqualTo(Array[Byte](1, 1, 1, 2, 2, 2, 2, 2)).
              await(1, timeout)
          }
        }
      }
    }

    "try to take at most up to 5 bytes" >> {
      def consumer5(implicit ee: EE) = consumeAtMost(Bytes(5))(ee.ec)
      val beLast = beTypedEqualTo[Chunk](Chunk.last)
      def beSomeLast(data: Byte*) =
        beTypedEqualTo[Chunk](Chunk last data.toArray)

      def beNonEmpty(data: Byte*) = beTypedEqualTo[Chunk](Chunk(data.toArray))

      "from an empty source" in { implicit ee: EE =>
        withEnumerator() {
          _ |>>> consumer5 must beLast.await(1, timeout)
        }
      }

      "from a single part of less than 5 bytes" in { implicit ee: EE =>
        withEnumerator(1, 3) {
          _ |>>> consumer5 must beSomeLast(1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of exactly 5 bytes" in { implicit ee: EE =>
        withEnumerator(1, 5) {
          _ |>>> consumer5 must beSomeLast(1, 1, 1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of more than 5 bytes" in { implicit ee: EE =>
        withEnumerator(1, 7) { source =>
          def take1 = source |>>> consumer5
          def take2 = source |>>> {
            Enumeratee.grouped(consumer5) &>> Iteratee.getChunks
          }

          take1 must beNonEmpty(1, 1, 1, 1, 1).await(1, timeout) and (
            take2.aka("take all") must beLike[List[Chunk]] {
              case _1 :: _2 :: Nil =>
                _1 must beNonEmpty(1, 1, 1, 1, 1) and (_2 must beSomeLast(1, 1))
            }.await(1, timeout)
          )

        }
      }

      "from a 1st part of 5B and the 2nd of 3B" in { implicit ee: EE =>
        withEnumerator(1, 5) { part1 =>
          withEnumerator(1, 3, 2) { part2 =>
            val source = part1 >>> part2
            def take1 = source |>>> consumer5
            def take2 = source |>>> {
              Enumeratee.grouped(consumer5) &>> Iteratee.getChunks
            }

            take1 must beNonEmpty(1, 1, 1, 1, 1).await(1, timeout) and (
              take2 must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must beNonEmpty(1, 1, 1, 1, 1) and (
                    _2 must beSomeLast(2, 2, 2)
                  )
              }.await(1, timeout)
            )
          }
        }
      }

      "from a 1st part of 3B and the 2nd of 5B" in { implicit ee: EE =>
        withEnumerator(1, 3) { part1 =>
          withEnumerator(1, 5, 2) { part2 =>
            val source = part1 >>> part2
            def take1 = source |>>> consumer5
            def take2 = source |>>> {
              Enumeratee.grouped(consumer5) &>> Iteratee.getChunks
            }

            take1 must beNonEmpty(1, 1, 1, 2, 2).await(1, timeout) and (
              take2 must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must beNonEmpty(1, 1, 1, 2, 2) and (
                    _2 must beSomeLast(2, 2, 2)
                  )
              }.await(1, timeout)
            )
          }
        }
      }
    }
  }

  // ---

  type EE = org.specs2.concurrent.ExecutionEnv

  /**
   * @param count the number of chunks
   * @param chunkSz the chunk size
   */
  def withEnumerator[T](count: Int = 0, chunkSz: Int = 0, by: Byte = 1)(f: Enumerator[Array[Byte]] => T)(implicit ec: ExecutionContext): T = {
    val chunk = Array.fill[Byte](chunkSz)(by)
    f(Enumerators.repeat(count) { chunk })
  }
}

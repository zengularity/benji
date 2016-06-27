package tests

import scala.concurrent.Future
import scala.concurrent.duration.{ Duration, FiniteDuration }

import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.{ Source, Sink }

import com.zengularity.storage.{ Chunk, Bytes, Streams }

class StreamSpec extends org.specs2.mutable.Specification {
  "Streams" title

  val timeout = Duration("5s").asInstanceOf[FiniteDuration]

  implicit lazy val system = akka.actor.ActorSystem("cabinet-core-tests")
  implicit def materializer = akka.stream.ActorMaterializer.create(system)

  "Consumer" should {
    "try to take at least up to 5 bytes" >> {
      def consumer5(implicit mat: Materializer): Sink[ByteString, Future[Seq[Chunk]]] = Streams.consumeAtLeast(Bytes(5)).toMat(Sink.seq[Chunk]) { (_, chunk) => chunk }

      def beSomeLast(data: Byte*) =
        beTypedEqualTo(Seq[Chunk](Chunk.last(ByteString(data.toArray))))

      "from an empty source" in { implicit ee: EE =>
        withSource() {
          _.runWith(consumer5) must beEqualTo(Seq.empty[Chunk]).
            await(1, timeout)
        }
      }

      "from a single part of less than 5 bytes" in { implicit ee: EE =>
        withSource(1, 3) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of exactly 5 bytes" in { implicit ee: EE =>
        withSource(1, 5) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of more than 5 bytes" in { implicit ee: EE =>
        withSource(1, 7) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1, 1, 1, 1, 1).
            await(1, timeout)
        }
      }

      "from a 1st part of 5B and the 2nd of 3B" in { implicit ee: EE =>
        withSource(1, 5) { part1 =>
          withSource(1, 3, 2) { part2 =>
            (part1 ++ part2).runWith(consumer5).
              map(_.toList) must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must beEqualTo(Chunk(ByteString(1, 1, 1, 1, 1))) and (
                    _2 must beEqualTo(Chunk.last(ByteString(2, 2, 2)))
                  )
              }.await(1, timeout)
          }
        }
      }

      "from a 1st part of 3B and the 2nd of 5B" in { implicit ee: EE =>
        withSource(1, 3) { part1 =>
          withSource(1, 5, 2) { part2 =>
            val source = part1 ++ part2

            source.runWith(consumer5) must beSomeLast(1, 1, 1, 2, 2, 2, 2, 2).
              await(1, timeout)
          }
        }
      }
    }

    "try to take at most up to 5 bytes" >> {
      def consumer5(implicit mat: Materializer): Sink[ByteString, Future[Seq[Chunk]]] = Streams.consumeAtMost(Bytes(5)).toMat(Sink.seq[Chunk]) { (_, chunk) => chunk }

      def beSomeLast(data: Byte*) =
        beTypedEqualTo(Seq[Chunk](Chunk.last(ByteString(data.toArray))))

      "from an empty source" in { implicit ee: EE =>
        withSource() {
          _.runWith(consumer5) must beEqualTo(Seq.empty[Chunk]).
            await(1, timeout)
        }
      }

      "from a single part of less than 5 bytes" in { implicit ee: EE =>
        withSource(1, 3) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of exactly 5 bytes" in { implicit ee: EE =>
        withSource(1, 5) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of more than 5 bytes" in { implicit ee: EE =>
        withSource(1, 7) {
          _.runWith(consumer5).map(_.toList) must beLike[List[Chunk]] {
            case _1 :: _2 :: Nil =>
              _1 must_== Chunk(ByteString(1, 1, 1, 1, 1)) and (
                _2 must_== Chunk.last(ByteString(1, 1))
              )

          }.await(1, timeout)
        }
      }

      "from a 1st part of 5B and the 2nd of 3B" in { implicit ee: EE =>
        withSource(1, 5) { part1 =>
          withSource(1, 3, 2) { part2 =>
            (part1 ++ part2).
              runWith(consumer5).map(_.toList) must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must_== Chunk(ByteString(1, 1, 1, 1, 1)) and (
                    _2 must_== Chunk.last(ByteString(2, 2, 2))
                  )
              }.await(1, timeout)
          }
        }
      }

      "from a 1st part of 3B and the 2nd of 5B" in { implicit ee: EE =>
        withSource(1, 3) { part1 =>
          withSource(1, 5, 2) { part2 =>
            (part1 ++ part2).
              runWith(consumer5).map(_.toList) must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must_== Chunk(ByteString(1, 1, 1, 2, 2)) and (
                    _2 must_== Chunk.last(ByteString(2, 2, 2))
                  )
              }.await(1, timeout)
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
  def withSource[T](count: Int = 0, chunkSz: Int = 0, by: Byte = 1)(f: Source[ByteString, akka.NotUsed] => T): T = {
    def chunk = ByteString(Array.fill[Byte](chunkSz)(by))
    f(Source(List(chunk)))
  }
}

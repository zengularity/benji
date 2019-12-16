package com.zengularity.benji

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.util.ByteString
import akka.stream.scaladsl.{ Source, Sink }

import org.specs2.concurrent.ExecutionEnv
import org.specs2.matcher.MatchResult

@SuppressWarnings(Array("org.wartremover.warts.Any"))
final class StreamSpec(implicit ee: ExecutionEnv)
  extends org.specs2.mutable.Specification {

  "Streams" title

  val timeout = FiniteDuration(5L, java.util.concurrent.TimeUnit.SECONDS)

  private implicit lazy val system =
    akka.actor.ActorSystem("benji-core-tests")

  @com.github.ghik.silencer.silent
  private implicit def materializer =
    akka.stream.ActorMaterializer.create(system)

  // import akka.stream.contrib.TestKit.assertAllStagesStopped
  protected def assertAllStagesStopped[T](f: => T): T = f

  "Consumer" should {
    "try to take at least up to 5 bytes" >> {
      def consumer5: Sink[ByteString, Future[Seq[Chunk]]] = Streams.consumeAtLeast(Bytes(5)).toMat(Sink.seq[Chunk]) { (_, chunk) => chunk }

      def beSomeLast(data: Byte*) =
        beTypedEqualTo(Seq[Chunk](Chunk.last(ByteString(data.toArray))))

      "from an empty source" in assertAllStagesStopped {
        withSource() {
          _.runWith(consumer5) must beEqualTo(Seq.empty[Chunk]).
            await(1, timeout)
        }
      }

      "from a single part of less than 5 bytes" in assertAllStagesStopped {
        withSource(3) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of exactly 5 bytes" in assertAllStagesStopped {
        withSource(5) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1, 1, 1).
            await(1, timeout)
        }
      }

      "from a single part of more than 5 bytes" in assertAllStagesStopped {
        withSource(7) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1, 1, 1, 1, 1).
            await(1, timeout)
        }
      }

      "from a 1st part of 5B and the 2nd of 3B" in assertAllStagesStopped {
        withSource(5) { part1 =>
          withSource(3, 2) { part2 =>
            (part1 ++ part2).runWith(consumer5).
              map(_.toList) must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must beEqualTo(Chunk(ByteString(1, 1, 1, 1, 1))) and (
                    _2 must beEqualTo(Chunk.last(ByteString(2, 2, 2))))
              }.await(1, timeout)
          }
        }
      }

      "from a 1st part of 3B and the 2nd of 5B" in assertAllStagesStopped {
        withSource(3) { part1 =>
          withSource(5, 2) { part2 =>
            val source = part1 ++ part2

            source.runWith(consumer5) must beSomeLast(1, 1, 1, 2, 2, 2, 2, 2).
              await(1, timeout)
          }
        }
      }
    }

    "try to take at most up to 5 bytes" >> {
      def consumer5: Sink[ByteString, Future[Seq[Chunk]]] = Streams.consumeAtMost(Bytes(5)).toMat(Sink.seq[Chunk]) { (_, chunk) => chunk }

      def beSomeLast(data: Byte*) =
        beTypedEqualTo(Seq[Chunk](Chunk.last(ByteString(data.toArray))))

      "from an empty source" in assertAllStagesStopped {
        withSource() {
          _.runWith(consumer5) must beEqualTo(Seq.empty[Chunk]).
            await(1, timeout)
        }
      }

      "from a single part of less than 5 bytes" in assertAllStagesStopped {
        assertAllStagesStopped {
          withSource(3) {
            _.runWith(consumer5) must beSomeLast(1, 1, 1).await(1, timeout)
          }
        }
      }

      "from a single part of exactly 5 bytes" in assertAllStagesStopped {
        withSource(5) {
          _.runWith(consumer5) must beSomeLast(1, 1, 1, 1, 1).await(1, timeout)
        }
      }

      "from a single part of more than 5 bytes" in assertAllStagesStopped {
        withSource(7) {
          _.runWith(consumer5).map(_.toList) must beLike[List[Chunk]] {
            case _1 :: _2 :: Nil =>
              _1 must_== Chunk(ByteString(1, 1, 1, 1, 1)) and (
                _2 must_== Chunk.last(ByteString(1, 1)))

          }.await(1, timeout)
        }
      }

      "from a 1st part of 5B and the 2nd of 3B" in assertAllStagesStopped {
        withSource(5) { part1 =>
          withSource(3, 2) { part2 =>
            (part1 ++ part2).
              runWith(consumer5).map(_.toList) must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must_== Chunk(ByteString(1, 1, 1, 1, 1)) and (
                    _2 must_== Chunk.last(ByteString(2, 2, 2)))
              }.await(1, timeout)
          }
        }
      }

      "from a 1st part of 3B and the 2nd of 5B" in assertAllStagesStopped {
        withSource(3) { part1 =>
          withSource(5, 2) { part2 =>
            (part1 ++ part2).
              runWith(consumer5).map(_.toList) must beLike[List[Chunk]] {
                case _1 :: _2 :: Nil =>
                  _1 must_== Chunk(ByteString(1, 1, 1, 2, 2)) and (
                    _2 must_== Chunk.last(ByteString(2, 2, 2)))
              }.await(1, timeout)
          }
        }
      }
    }
  }

  // ---

  def repeat[E](numberOfTimes: Int)(element: => E): Source[E, akka.NotUsed] =
    Source.unfold(numberOfTimes) {
      case remaining if remaining > 0 => Some((remaining - 1, element))
      case _ => None
    }

  /**
   * @param chunkSz the chunk size
   */
  def withSource(chunkSz: Int = 0, by: Byte = 1)(f: Source[ByteString, akka.NotUsed] => MatchResult[Future[Seq[Chunk]]]): MatchResult[Future[Seq[Chunk]]] = {
    def chunk = ByteString(Array.fill[Byte](chunkSz)(by))

    f(Source(List(chunk)))
  }
}

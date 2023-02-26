package tests.benji

import scala.concurrent.{ ExecutionContext, Future }

import akka.util.ByteString

import akka.stream.Materializer
import akka.stream.scaladsl.{ Sink, Source }

/** Contains additional sources for testing. */
object StreamUtils {

  /**
   * Like the built-in [[Source.repeat]],
   * but we'll only do it a certain number of times - not forever.
   */
  def repeat[E](numberOfTimes: Int)(element: => E): Source[E, akka.NotUsed] =
    Source.unfold(numberOfTimes) {
      case remaining if remaining > 0 => Some((remaining - 1, element))
      case _                          => None
    }

  def consume(
      implicit
      m: Materializer
    ): Sink[ByteString, Future[String]] = {
    implicit def ec: ExecutionContext = m.executionContext

    Sink
      .fold[StringBuilder, ByteString](new StringBuilder()) {
        _ ++= _.utf8String
      }
      .mapMaterializedValue(_.map(_.result()))
  }
}

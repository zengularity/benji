package tests

import akka.stream.scaladsl.Source

/** Contains additional sources for testing. */
object Sources {

  /**
   * Like the built-in [[Source.repeat]],
   * but we'll only do it a certain number of times - not forever.
   */
  def repeat[E](numberOfTimes: Int)(element: => E): Source[E, akka.NotUsed] =
    Source.unfold(numberOfTimes) {
      case remaining if remaining > 0 => Some((remaining - 1, element))
      case _ => None
    }
}

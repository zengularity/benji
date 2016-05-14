package tests

import play.api.libs.iteratee._

import scala.concurrent.ExecutionContext

/**
 * Contains additional Enumerator implementations
 * that aren't provided by default.
 */
object Enumerators {

  /**
   * Like the built-in [[Enumerator.repeat)]],
   * but we'll only do it a certain number of times - not forever.
   */
  def repeat[E](numberOfTimes: Int)(element: => E)(implicit ec: ExecutionContext): Enumerator[E] = Enumerator.unfold(numberOfTimes) {
    case remaining if remaining > 0 => Some((remaining - 1, element))
    case _                          => None
  }
}

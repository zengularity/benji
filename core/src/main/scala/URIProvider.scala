package com.zengularity.benji

import java.net.URI

import scala.util.{ Success, Try }

/**
 * Typeclass to try to create an URI from a T
 */
sealed trait URIProvider[T] {
  def apply(config: T): Try[URI]
}

object URIProvider {
  /**
   * Creates a URIProvider from a function.
   */
  def apply[T](f: T => Try[URI]): URIProvider[T] = new URIProvider[T] {
    def apply(config: T): Try[URI] = f(config)
  }

  /**
   * UriProvider to provide an URI directly
   */
  implicit val idInstance: URIProvider[URI] = URIProvider[URI](Success(_))

  /**
   * UriProvider to provide an URI from a string
   */
  implicit val fromStringInstance: URIProvider[String] = URIProvider[String](s => Try { new URI(s) })
}

package com.zengularity.benji.s3

import java.net.URI

import scala.util.{ Success, Try }

/**
 * Typeclass to try to create an URI from a T
 */
sealed trait S3URIProvider[T] {
  def apply(config: T): Try[URI]
}

object S3URIProvider {
  /**
   * Creates a S3URIProvider from a function.
   */
  def apply[T](f: T => Try[URI]) = new S3URIProvider[T] {
    def apply(config: T) = f(config)
  }

  /**
   * S3UriProvider to provide an URI directly
   */
  implicit val idInstance = S3URIProvider[URI](Success(_))

  /**
   * S3UriProvider to provide an URI from a string
   */
  implicit val fromStringInstance = S3URIProvider[String](s => Try { new URI(s) })
}

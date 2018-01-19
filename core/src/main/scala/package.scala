package com.zengularity

import scala.util.control.NonFatal

package object benji {
  /** Extracts an long integer from a string representation */
  object LongVal {
    def unapply(value: String): Option[Long] = try {
      def i = value.toLong
      Some(i)
    } catch {
      case NonFatal(_) => Option.empty[Long]
    }
  }
}

import sbt.Keys._
import sbt._

import wartremover.{
  Warts, wartremoverClasspaths, wartremoverExcluded, wartremoverErrors
}

object Wart {
  import wartremover.Wart.{
    DefaultArguments,
    Overloading,
    Equals,
    FinalCaseClass,
    ImplicitParameter,
    NonUnitStatements
  }

  val settings = Seq(
    wartremoverErrors ++= Warts.allBut(
      DefaultArguments,
      Overloading,
      Equals,
      FinalCaseClass,
      ImplicitParameter,
      NonUnitStatements),
    scalacOptions in Test ~= {
      def wartFilter(w: String): String => Boolean = (_: String).startsWith(
        s"-P:wartremover:traverser:org.wartremover.warts.$w")

      // Wart doesn't properly handle specs2 variance
      val anyFilter = wartFilter("Any")
      val anyValFilter = wartFilter("AnyVal")
      val nothingFilter = wartFilter("Nothing")
      val serializableFilter = wartFilter("Serializable")
      val javaSerializableFilter = wartFilter("JavaSerializable")
      val productFilter = wartFilter("Product")

      val filter: String => Boolean = { s =>
        anyFilter(s) || anyValFilter(s) || productFilter(s) ||
        nothingFilter(s) || serializableFilter(s) || javaSerializableFilter(s)
      }

      (_: Seq[String]).filterNot(filter)
    },
    scalacOptions in (Compile, console) ~= {
      _.filterNot(_.contains("wartremover"))
    },
    scalacOptions in (Test, console) ~= {
      _.filterNot(_.contains("wartremover"))
    }
  )
}

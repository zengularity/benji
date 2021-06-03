import sbt.Keys._
import sbt._

import wartremover.{
  Warts,
  wartremoverClasspaths,
  wartremoverExcluded,
  wartremoverErrors
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

  def wartFilter(w: String): String => Boolean = (_: String).startsWith(
    s"-P:wartremover:traverser:org.wartremover.warts.$w")

  val settings = Seq(
    wartremoverErrors ++= Warts.allBut(
      DefaultArguments,
      Overloading,
      Equals,
      FinalCaseClass,
      ImplicitParameter,
      NonUnitStatements),
    scalacOptions ~= {
      val nothingFilter = wartFilter("Nothing")
      val javaConvFilter = wartFilter("JavaConversions") // 2.11 compat

      val filter: String => Boolean = { s =>
        nothingFilter(s) || javaConvFilter(s)
      }

      (_: Seq[String]).filterNot(filter)
    },
    Test / scalacOptions ~= {
      // Wart doesn't properly handle specs2 variance
      val anyFilter = wartFilter("Any")
      val anyValFilter = wartFilter("AnyVal")
      val serializableFilter = wartFilter("Serializable")
      val javaSerializableFilter = wartFilter("JavaSerializable")
      val productFilter = wartFilter("Product")

      val filter: String => Boolean = { s =>
        anyFilter(s) || anyValFilter(s) || productFilter(s) ||
          serializableFilter(s) || javaSerializableFilter(s)
      }

      (_: Seq[String]).filterNot(filter)
    },
    Compile / console / scalacOptions ~= {
      _.filterNot(_.contains("wartremover"))
    },
    Compile / doc / scalacOptions ~= {
      _.filterNot(_ startsWith "-P:wartremover")
    },
    Test / console / scalacOptions ~= {
      _.filterNot(_.contains("wartremover"))
    })
}

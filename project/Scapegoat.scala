import sbt.Keys._
import sbt._

object Scapegoat {
  import com.sksamuel.scapegoat.sbt.ScapegoatSbtPlugin.autoImport._

  lazy val settings = Seq(
    scapegoatVersion in ThisBuild := "1.3.3",
    scapegoatReports in ThisBuild := Seq("xml"),
    scapegoatDisabledInspections in ThisBuild := Seq("FinalModifierOnCaseClass")
  )
}

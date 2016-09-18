import sbt.Keys._
import sbt._

object Common {
  val akkaVer = "2.4.10"

  val settings = Seq(
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    autoAPIMappings := true,
    libraryDependencies ++= wsStream ++ Seq(
      "specs2-core", "specs2-junit").map(
      "org.specs2" %% _ % "3.7.3" % Test) ++ Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer,
        "com.typesafe.akka" %% "akka-stream-contrib" % "0.3-9-gaeac7b2"
    ).map(_ % Test)
  ) ++ Format.settings ++ Findbugs.settings

  val wsStream = Seq(
    Dependencies.playWS % "provided",
    "com.typesafe.akka" %% "akka-stream" % akkaVer % "provided")

}

object Findbugs {
  import scala.xml.{ NodeSeq, XML }, XML.{ loadFile => loadXML }

  import de.johoop.findbugs4sbt.{ FindBugs, ReportType }, FindBugs.{
    findbugsExcludeFilters, findbugsReportPath, findbugsReportType,
    findbugsSettings
  }

  val settings = findbugsSettings ++ Seq(
    findbugsReportType := Some(ReportType.PlainHtml),
    findbugsReportPath := Some(target.value / "findbugs.html"),
    findbugsExcludeFilters := {
      val commonFilters = loadXML(baseDirectory.value / ".." / "project" / (
        "findbugs-exclude-filters.xml"))

      val filters = {
        val f = baseDirectory.value / "findbugs-exclude-filters.xml"
        if (!f.exists) NodeSeq.Empty else loadXML(f).child
      }

      Some(
        <FindBugsFilter>${commonFilters.child}${filters}</FindBugsFilter>
      )
    }
  )
}

object Format {
  import com.typesafe.sbt.SbtScalariform._
  import scalariform.formatter.preferences._

  val settings = scalariformSettings ++ Seq(
    ScalariformKeys.preferences := ScalariformKeys.preferences.value.
      setPreference(AlignParameters, false).
      setPreference(AlignSingleLineCaseStatements, true).
      setPreference(CompactControlReadability, false).
      setPreference(CompactStringConcatenation, false).
      setPreference(DoubleIndentClassDeclaration, true).
      setPreference(FormatXml, true).
      setPreference(IndentLocalDefs, false).
      setPreference(IndentPackageBlocks, true).
      setPreference(IndentSpaces, 2).
      setPreference(MultilineScaladocCommentsStartOnFirstLine, false).
      setPreference(PreserveSpaceBeforeArguments, false).
      setPreference(PreserveDanglingCloseParenthesis, true).
      setPreference(RewriteArrowSymbols, false).
      setPreference(SpaceBeforeColon, false).
      setPreference(SpaceInsideBrackets, false).
      setPreference(SpacesWithinPatternBinders, true)
  )
}  

object Dependencies {
  val playWS = "com.typesafe.play" %% "play-ws" % "2.5.6"
}

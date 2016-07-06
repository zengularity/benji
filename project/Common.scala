import sbt.Keys._
import sbt._

object Common {
  val settings = Seq(
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    autoAPIMappings := true,
    libraryDependencies ++= Seq(Dependencies.playWS % "provided") ++ Seq(
      "specs2-core", "specs2-junit").map(
      "org.specs2" %% _ % "3.7.3" % Test)
  ) ++ Format.settings

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
  val playWS = "com.typesafe.play" %% "play-ws" % "2.5.4"
}

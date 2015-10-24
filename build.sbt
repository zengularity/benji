organization := "com.zengularity"

name := "s3"

version := "1.0-SNAPSHOT"

scalaVersion := "2.11.7"

val PlayVersion = "2.4.2"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws" % PlayVersion % "provided")

// Test
libraryDependencies ++= Seq("specs2-core", "specs2-junit").map(
  "org.specs2" %% _ % "3.6.4" % Test) ++ Seq(
  "com.typesafe" % "config" % "1.3.0" % Test)

fork in Test := false

testOptions in Test += Tests.Cleanup(cl => {
  import scala.language.reflectiveCalls
  val c = cl.loadClass("tests.TestUtils$")
  type M = { def close(): Unit }
  val m: M = c.getField("MODULE$").get(null).asInstanceOf[M]
  m.close()
})

autoAPIMappings := true

scalacOptions in (Compile,doc) := Seq("-diagrams")

import scalariform.formatter.preferences._

scalariformSettings

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

//  .settings(
// net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)

organization := "com.zengularity"

name := "cabinet-core"

version := "1.2.0-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-ws" % "2.4.6",
  "joda-time" % "joda-time" % "2.9.3",
  "commons-codec" % "commons-codec" % "1.10",
  "org.slf4j" % "slf4j-api" % "1.7.21" % "provided"
)

// Test
libraryDependencies ++= Seq("specs2-core", "specs2-junit").map(
  "org.specs2" %% _ % "3.7.3" % Test)

autoAPIMappings := true

scalacOptions in (Compile, doc) := Seq("-diagrams")

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

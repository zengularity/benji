organization := "com.zengularity"

name := "cabinet-s3"

version := "1.2.0-SNAPSHOT"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  organization.value %% "cabinet-core" % version.value % "provided"
)

// Test
libraryDependencies ++= Seq("specs2-core", "specs2-junit").map(
  "org.specs2" %% _ % "3.7.3" % Test) ++ Seq(
  "com.typesafe" % "config" % "1.3.0" % Test)

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

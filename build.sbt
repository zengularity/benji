import sbtunidoc.Plugin.UnidocKeys._
import Dependencies._

organization in ThisBuild := "com.zengularity"

version in ThisBuild := "1.3.0-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

lazy val core = project.in(file("core")).
  settings(Common.settings: _*).settings(
    name := "cabinet-core",
    libraryDependencies ++= Seq(
      "joda-time" % "joda-time" % "2.9.3",
      "commons-codec" % "commons-codec" % "1.10",
      "org.slf4j" % "slf4j-api" % "1.7.21" % "provided"
    )
  )

lazy val s3 = project.in(file("s3")).
  settings(Common.settings: _*).settings(
    name := "cabinet-s3",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.5" % "provided")
  ).dependsOn(core)

lazy val google = project.in(file("google")).
  settings(Common.settings: _*).settings(
    name := "cabinet-google",
    libraryDependencies ++= Seq(
      "com.google.apis" % "google-api-services-storage" % "v1-rev68-1.21.0")
  ).dependsOn(core)

lazy val cabinet = (project in file(".")).
  settings(unidocSettings: _*).
  settings(
    libraryDependencies += playWS % "provided",
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor")).
  dependsOn(s3, google).
  aggregate(core, s3, google)

publishTo in ThisBuild := Some {
  import Resolver.mavenStylePatterns

  val root = new java.io.File(".")

  Resolver.file("local-repo", root / "target" / "local-repo")
}

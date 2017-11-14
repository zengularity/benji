import sbtunidoc.Plugin.UnidocKeys._
import Common._

organization in ThisBuild := "com.zengularity"

version in ThisBuild := "1.3.3-SNAPSHOT"

scalaVersion in ThisBuild := "2.11.8"

resolvers in ThisBuild ++= Seq(
  // For Akka Stream Contrib TestKit
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/snapshots")

lazy val core = project.in(file("core")).
  settings(Common.settings: _*).settings(
    name := "cabinet-core",
    libraryDependencies ++= Seq(
      "joda-time" % "joda-time" % "2.9.9",
      "commons-codec" % "commons-codec" % "1.10",
      "org.slf4j" % "slf4j-api" % "1.7.21" % Provided
    )
  )

lazy val s3 = project.in(file("s3")).
  settings(Common.settings: _*).settings(
    name := "cabinet-s3",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-xml" % "1.0.5" % Provided)
  ).dependsOn(core)

lazy val google = project.in(file("google")).
  settings(Common.settings: _*).settings(
    name := "cabinet-google",
    libraryDependencies ++= Seq(
      Dependencies.playJson,
      "com.google.apis" % "google-api-services-storage" % "v1-rev112-1.23.0")
  ).dependsOn(core)

lazy val vfs = project.in(file("vfs")).
  settings(Common.settings: _*).settings(
    name := "cabinet-vfs",
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-vfs2" % "2.1",
      "commons-io" % "commons-io" % "2.4" % Test)
  ).dependsOn(core)

lazy val cabinet = (project in file(".")).
  settings(unidocSettings: _*).
  settings(
    libraryDependencies ++= wsStream,
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
    scalacOptions in (Compile, doc) ++= List(
      "-skip-packages", "highlightextractor")).
  dependsOn(s3, google, vfs).
  aggregate(core, s3, google, vfs)

publishTo in ThisBuild := Some {
  import Resolver.mavenStylePatterns

  val root = new java.io.File(".")

  Resolver.file("local-repo", root / "target" / "local-repo")
}

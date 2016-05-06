organization := "com.zengularity"

version := "1.2.0-SNAPSHOT"

scalaVersion := "2.11.8"

autoAPIMappings := true

lazy val core = project.in(file("core"))

lazy val s3 = project.in(file("s3")).dependsOn(core)

lazy val google = project.in(file("google")).dependsOn(core)

lazy val cabinet = (project in file(".")).
  settings(unidocSettings: _*).
  settings(
    name := "doc",
    scalaVersion := "2.11.8",
    scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked")).
  dependsOn(s3, google).
  aggregate(core, s3, google)

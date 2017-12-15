import Common._

organization in ThisBuild := "com.zengularity"

scalaVersion in ThisBuild := "2.12.4"

crossScalaVersions in ThisBuild := Seq("2.11.11", scalaVersion.value)

lazy val core = project.in(file("core")).
  settings(Common.settings: _*).settings(
    name := "benji-core",
    libraryDependencies ++= Seq(
      "commons-codec" % "commons-codec" % "1.10",
      "org.slf4j" % "slf4j-api" % "1.7.21" % Provided
    )
  )

val scalaXmlVer = Def.setting[String] {
  if (scalaVersion.value startsWith "2.11") "1.0.5"
  else "1.0.6"
}

lazy val s3 = project.in(file("s3")).
  settings(Common.settings: _*).settings(
    name := "benji-s3",
    libraryDependencies ++= Seq(
      Dependencies.playWSXml,
      "org.scala-lang.modules" %% "scala-xml" % scalaXmlVer.value % Provided)
  ).dependsOn(core)

lazy val google = project.in(file("google")).
  settings(Common.settings: _*).settings(
    name := "benji-google",
    libraryDependencies ++= Seq(
      Dependencies.playWSJson,
      "com.google.apis" % "google-api-services-storage" % "v1-rev112-1.23.0")
  ).dependsOn(core)

lazy val vfs = project.in(file("vfs")).
  settings(Common.settings: _*).settings(
    name := "benji-vfs",
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-vfs2" % "2.1",
      "commons-io" % "commons-io" % "2.4" % Test)
  ).dependsOn(core)

lazy val benji = (project in file(".")).
  enablePlugins(ScalaUnidocPlugin).
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

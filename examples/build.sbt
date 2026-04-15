ThisBuild / organization := "com.zengularity"

ThisBuild / scalaVersion := "2.12.11"

ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % "early-semver"
)

ThisBuild / evictionErrorLevel := Level.Warn

ThisBuild / scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-target:jvm-1.8",
  "-Ywarn-adapted-args",
  "-Ywarn-inaccessible",
  "-Ywarn-nullary-override",
  "-Ywarn-infer-any",
  "-Ywarn-dead-code",
  "-Ywarn-unused",
  "-Ywarn-value-discard",
  "-unchecked",
  "-deprecation",
  "-feature",
  "-g:vars",
  "-Xlint:_",
  "-opt:_"
)

ThisBuild / dynverVTagPrefix := false

ThisBuild / resolvers ++= Seq(
  "Sonatype Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
)

val playVer = Def.setting[String] {
  if ((ThisBuild / version).value endsWith "-SNAPSHOT") {
    s"${(ThisBuild / version).value.dropRight(9)}-play26-SNAPSHOT"
  } else {
    s"${(ThisBuild / version).value}-play26"
  }
}

lazy val playS3 = project
  .in(file("play-s3-runtime-di"))
  .enablePlugins(PlayScala)
  .settings(
    name := "benji-s3-play-demo",
    libraryDependencies ++= Seq(
      guice,
      "com.typesafe.play" %% "play-ws-standalone" % "2.0.8",
      "com.zengularity" %% "benji-s3" % version.value,
      "com.zengularity" %% "benji-play" % playVer.value
    )
  )

lazy val playVfs = project
  .in(file("play-vfs-compile-di"))
  .enablePlugins(PlayScala)
  .settings(
    name := "benji-vfs-play-demo",
    libraryDependencies ++= Seq(
      "com.typesafe.play" %% "play-ws-standalone" % "2.0.8",
      "com.zengularity" %% "benji-vfs" % version.value,
      "com.zengularity" %% "benji-play" % playVer.value
    )
  )

lazy val examples = (project in file(".")).aggregate(playS3, playVfs)

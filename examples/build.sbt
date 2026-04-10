ThisBuild / organization := "com.zengularity"

ThisBuild / scalaVersion := "2.12.11"

ThisBuild / crossScalaVersions := Seq("2.11.12", scalaVersion.value)

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

ThisBuild / version := "2.3.0-67fd4f9b-SNAPSHOT"

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
      "com.zengularity" %% "benji-vfs" % version.value,
      "com.zengularity" %% "benji-play" % playVer.value
    )
  )

lazy val examples = (project in file("."))
  .aggregate(playS3, playVfs)

name := "benji-s3-play-demo"

scalaVersion := "2.12.11"

crossScalaVersions in ThisBuild := Seq("2.11.12", scalaVersion.value)

scalacOptions ++= Seq(
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

version := "2.2.0-SNAPSHOT"

val playVer = Def.setting[String] {
  if (version.value endsWith "-SNAPSHOT") {
    s"${version.value.dropRight(9)}-play27-SNAPSHOT"
  } else {
    s"${version.value}-play27"
  }
}

libraryDependencies ++= Seq(
  guice,
  "com.zengularity" %% "benji-s3" % version.value,
  "com.zengularity" %% "benji-play" % playVer.value
)

lazy val playS3 = (project in file(".")).enablePlugins(PlayScala)

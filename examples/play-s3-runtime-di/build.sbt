name := "benji-s3-play-demo"

scalaVersion := "2.12.4"

scalacOptions ++= Seq(
  "-encoding", "UTF-8",
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

libraryDependencies ++= Seq(
  guice,
  "com.zengularity" %% "benji-s3" % "1.4.0-SNAPSHOT",
  "com.zengularity" %% "benji-play" % "1.4.0-SNAPSHOT",
)

lazy val playS3 = (project in file(".")).enablePlugins(PlayScala)
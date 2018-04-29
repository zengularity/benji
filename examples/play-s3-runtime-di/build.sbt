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

resolvers ++= Seq( 
  "Entrepot Releases" at "https://raw.github.com/zengularity/entrepot/master/releases",
  "Entrepot Snapshots" at "https://raw.github.com/zengularity/entrepot/master/snapshots"
)

libraryDependencies ++= Seq(
  guice,
  "com.zengularity" %% "benji-s3" % "2.0.0",
  "com.zengularity" %% "benji-play" % "2.0.0",
)

lazy val playS3 = (project in file(".")).enablePlugins(PlayScala)

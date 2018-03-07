name := "benji-vfs-play-demo"

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
  "com.zengularity" %% "benji-vfs" % "2.0.0-SNAPSHOT",
  "com.zengularity" %% "benji-play" % "2.0.0-SNAPSHOT",
)

lazy val playVfs = (project in file(".")).enablePlugins(PlayScala)

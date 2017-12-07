import sbt.Keys._
import sbt._

object Common {
  val akkaVer = "2.5.4"

  val settings = Seq(
    scalacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-target:jvm-1.8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings",
      "-Xlint",
      "-Ywarn-numeric-widen",
      "-Ywarn-infer-any",
      "-Ywarn-dead-code",
      "-Ywarn-unused",
      "-Ywarn-unused-import",
      "-Ywarn-value-discard",
      "-g:vars"
    ),
    scalacOptions in (Compile, console) ~= {
      _.filterNot { opt => opt.startsWith("-X") || opt.startsWith("-Y") }
    },
    scalacOptions in (Test, console) ~= {
      _.filterNot { opt => opt.startsWith("-X") || opt.startsWith("-Y") }
    },
    scalacOptions in Test ++= Seq("-Yrangepos"),
    fork in Test := true,
    autoAPIMappings := true,
    libraryDependencies ++= wsStream ++ Seq(
      "specs2-core", "specs2-junit").map(
      "org.specs2" %% _ % "4.0.1" % Test) ++ Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer,
        "com.typesafe.akka" %% "akka-stream-contrib" % "0.8",
        "ch.qos.logback" % "logback-classic" % "1.1.7"
    ).map(_ % Test)
  ) ++ Scalariform.settings ++ Scapegoat.settings /*++ Findbugs.settings*/

  val wsStream = Seq(
    Dependencies.playWS % Provided,
    "com.typesafe.akka" %% "akka-stream" % akkaVer % Provided)

}

object Dependencies {
  private val version = "1.1.3"
  val playWS = "com.typesafe.play" %% "play-ahc-ws-standalone" % version
  val playJson = "com.typesafe.play" %% "play-ws-standalone-json" % version
  val playXml = "com.typesafe.play" %% "play-ws-standalone-xml" % version
}

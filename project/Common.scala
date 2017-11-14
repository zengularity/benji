import sbt.Keys._
import sbt._

object Common {
  val akkaVer = "2.4.12"

  val settings = Seq(
    scalacOptions ++= Seq("-unchecked", "-deprecation", "-Xlint",
      "-Ywarn-unused-import", "-Ywarn-unused", "-Ywarn-dead-code",
      "-Ywarn-numeric-widen"),
    autoAPIMappings := true,
    libraryDependencies ++= wsStream ++ Seq(
      "specs2-core", "specs2-junit").map(
      "org.specs2" %% _ % "3.8.6" % Test) ++ Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer,
        "com.typesafe.akka" %% "akka-stream-contrib" % "0.6",
        "ch.qos.logback" % "logback-classic" % "1.1.7"
    ).map(_ % Test)
  ) ++ Scalariform.settings ++ Findbugs.settings

  val wsStream = Seq(
    Dependencies.playWS % Provided,
    "com.typesafe.akka" %% "akka-stream" % akkaVer % Provided)

}

object Dependencies {
  val playVer = "2.5.10"
  val playWS = "com.typesafe.play" %% "play-ws" % playVer
  val playJson = "com.typesafe.play" %% "play-json" % playVer
}

import sbt.Keys._
import sbt._

import com.typesafe.tools.mima.plugin.MimaKeys._

object Common {
  val akkaVer = "2.5.4"

  val previousRelease = "1.3.3"

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
    scalacOptions in (Compile, doc) ~= {
      _.filterNot { opt => opt.startsWith("-X") || opt.startsWith("-Y") }
    },
    scalacOptions in Test ++= Seq("-Yrangepos"),
    fork in Test := true,
    mimaPreviousArtifacts := Set(
      /* organization.value %% name.value % previousRelease */),
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
  object Version {
    val playWS = "1.1.3"
  }

  val playWS = "com.typesafe.play" %% "play-ahc-ws-standalone" % Version.playWS
  val playWSJson = "com.typesafe.play" %% "play-ws-standalone-json" % Version.playWS
  val playWSXml = "com.typesafe.play" %% "play-ws-standalone-xml" % Version.playWS
}

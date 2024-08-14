import sbt.Keys._
import sbt._

object Dependencies {

  object Version {

    val playWS: Def.Initialize[String] = Def.setting[String] {
      sys.env.getOrElse(
        "WS_VERSION", {
          if (scalaBinaryVersion.value == "3") {
            "2.2.0-M1+68-da80b259-SNAPSHOT"
          } else {
            "2.0.8" // upper 2.0.6
          }
        }
      )
    }

    val play: Def.Initialize[String] = Def.setting[String] {
      val v = scalaBinaryVersion.value
      val lower = {
        if (v == "3" || v == "2.13") "2.8.13"
        else "2.6.25"
      }

      sys.env.getOrElse("PLAY_VERSION", lower)
    }

    val akka = Def.setting[String] {
      if (scalaBinaryVersion.value startsWith "3") "2.6.19"
      else if (play.value startsWith "2.8.") "2.6.1"
      else "2.5.21"
    }

    val playJson: Def.Initialize[String] = Def.setting[String] {
      val v = scalaBinaryVersion.value
      val lower = {
        if (v startsWith "3") "2.10.0-RC5"
        else if (v == "2.13") "2.7.4"
        else "2.6.14"
      }

      sys.env.getOrElse("PLAY_JSON_VERSION", lower)
    }
  }

  val playWS = Def.setting(exclude {
    "com.typesafe.play" %% "play-ws-standalone" % Version.playWS.value
  })

  lazy val wsStream = Def.setting[Seq[ModuleID]] {
    Seq(
      playWS.value % Provided,
      "com.typesafe.akka" %% "akka-stream" % Version.akka.value % Provided
    )
  }

  val playAhcWS = Def.setting {
    val cacheVer = {
      if (scalaBinaryVersion.value == "2.11") "1.1.7"
      else "2.2.0"
    }

    Seq(
      "com.typesafe.play" %% "play-ahc-ws-standalone" % Version.playWS.value,
      "com.typesafe.play" % "shaded-asynchttpclient" % Version.playWS.value,
      "com.typesafe.play" %% "cachecontrol" % cacheVer
    ).map(exclude)
  }

  val playWSJson = Def.setting {
    exclude(
      "com.typesafe.play" %% "play-ws-standalone-json" % Version.playWS.value
    )
  }

  val playWSXml = Def.setting {
    exclude(
      "com.typesafe.play" %% "play-ws-standalone-xml" % Version.playWS.value
    )
  }

  val slf4jApi = "org.slf4j" % "slf4j-api" % "2.0.16"

  private def exclude(mid: ModuleID) =
    mid
      .exclude("org.scala-lang.modules", "*")
      .exclude("com.typesafe.akka", "*")
      .exclude("com.typesafe.play", "*")
      .exclude("com.typesafe", "*")
}

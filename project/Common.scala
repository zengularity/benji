import sbt.Keys._
import sbt._

import com.typesafe.tools.mima.plugin.MimaKeys._

object Common extends AutoPlugin {
  import Dependencies.Version.{ akka => akkaVer }

  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin

  override def projectSettings = Seq(
    scalacOptions ++= Seq(
      "-encoding",
      "UTF-8",
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings"),
    scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq(
          "-target:jvm-1.8",
          "-Xlint",
          "-g:vars")
      } else Seq()
    },
    scalacOptions ++= {
      val sv = scalaBinaryVersion.value

      if (sv == "2.12") {
        Seq(
          "-Xmax-classfile-name",
          "128",
          "-Ywarn-numeric-widen",
          "-Ywarn-dead-code",
          "-Ywarn-value-discard",
          "-Ywarn-infer-any",
          "-Ywarn-unused",
          "-Ywarn-unused-import",
          "-Ywarn-macros:after")
      } else if (sv == "2.11") {
        Seq(
          "-Xmax-classfile-name",
          "128",
          "-Yopt:_",
          "-Ydead-code",
          "-Yclosure-elim",
          "-Yconst-opt")
      } else if (sv == "2.13") {
        Seq(
          "-explaintypes",
          "-Werror",
          "-Wnumeric-widen",
          "-Wdead-code",
          "-Wvalue-discard",
          "-Wextra-implicit",
          "-Wmacros:after",
          "-Wunused")
      } else {
        Seq(
          "-Wunused:all",
          "-language:implicitConversions",
          "-Wconf:cat=deprecation&msg=.*fromFuture.*:s")
      }
    },
    Compile / console / scalacOptions ~= {
      _.filterNot(o =>
        o.startsWith("-X") || o.startsWith("-Y") || o.startsWith("-P:silencer"))
    },
    Compile / doc / scalacOptions ~= {
      _.filterNot { opt =>
        opt.startsWith("-X") || opt.startsWith("-Y") || opt.startsWith("-P")
      }
    },
    Test / scalacOptions ~= {
      _.filterNot(_ == "-Xfatal-warnings")
    },
    Test / scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq("-Yrangepos")
      } else {
        Seq.empty
      }
    },
    resolvers += Resolver.sonatypeRepo("staging" /* releases */ ),
    libraryDependencies ++= {
      if (!scalaBinaryVersion.value.startsWith("3")) {
        val silencerVersion = "1.7.8"

        Seq(
          compilerPlugin(
            ("com.github.ghik" %% "silencer-plugin" % silencerVersion)
              .cross(CrossVersion.full)),
          ("com.github.ghik" %% "silencer-lib" % silencerVersion % Provided)
            .cross(CrossVersion.full))
      } else Seq.empty
    },
    libraryDependencies ++= Dependencies.wsStream.value ++ Seq(
      "specs2-core", "specs2-junit").map(d => {
        ("org.specs2" %% d % "4.10.6").cross(CrossVersion.for3Use2_13) % Test
      }) ++ Seq(
        "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value,
        "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value,
        "ch.qos.logback" % "logback-classic" % "1.2.11").map(_ % Test),
    Compile / compile / javacOptions ++= Seq(
      "-source", "1.8", "-target", "1.8"),
    Compile / console / scalacOptions ~= {
      _.filterNot { opt => opt.startsWith("-X") || opt.startsWith("-Y") }
    },
    Test / console / scalacOptions ~= {
      _.filterNot { opt => opt.startsWith("-X") || opt.startsWith("-Y") }
    },
    Compile / doc / scalacOptions ~= {
      _.filterNot { opt =>
        opt.startsWith("-X") || opt.startsWith("-Y") || opt.startsWith("-P")
      }
    },
    Test / scalacOptions ++= Seq("-Yrangepos"),
    Compile / unmanagedSourceDirectories += {
      val base = (Compile / sourceDirectory).value

      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n < 13 => base / "scala-2.13-"
        case _ => base / "scala-2.13+"
      }
    },
    Test / fork := true,
    ThisBuild / mimaFailOnNoPrevious := false,
    mimaPreviousArtifacts := Set( /* organization.value %% name.value % previousRelease */ ),
    autoAPIMappings := true,
    apiMappings ++= mappings("org.scala-lang", "http://scala-lang.org/api/%s/")("scala-library").value) ++ Wart.settings ++ Publish.settings

  // ---

  def mappings(org: String, location: String, revision: String => String = identity)(names: String*) = Def.task[Map[File, URL]] {
    (for {
      entry: Attributed[File] <- (Compile / fullClasspath).value
      module: ModuleID <- entry.get(moduleID.key)
      if module.organization == org
      if names.exists(module.name.startsWith)
      rev = revision(module.revision)
    } yield entry.data -> url(location.format(rev)))(scala.collection.breakOut)
  }

  import scala.xml.{ Elem => XmlElem, Node => XmlNode }
  def transformPomDependencies(tx: XmlElem => Option[XmlNode]): XmlNode => XmlNode = { node: XmlNode =>
    import scala.xml.{ NodeSeq, XML }
    import scala.xml.transform.{ RewriteRule, RuleTransformer }

    val tr = new RuleTransformer(new RewriteRule {
      override def transform(node: XmlNode): NodeSeq = node match {
        case e: XmlElem if e.label == "dependency" => tx(e) match {
          case Some(n) => n
          case _ => NodeSeq.Empty
        }

        case _ => node
      }
    })

    tr.transform(node).headOption match {
      case Some(transformed) => transformed
      case _ => sys.error("Fails to transform the POM")
    }
  }
}

object Dependencies {
  object Version {
    val playWS = sys.env.getOrElse("WS_VERSION", "2.0.8") // upper 2.0.6

    val play: Def.Initialize[String] = Def.setting[String] {
      val v = scalaBinaryVersion.value
      val lower = {
        if (v == "3" || v == "2.13") "2.8.8"
        else "2.6.25"
      }

      sys.env.getOrElse("PLAY_VERSION", lower)
    }

    val akka = Def.setting[String] {
      if (scalaBinaryVersion.value startsWith "3") "2.6.19"
      else if (play.value startsWith "2.8.") "2.6.1"
      else "2.5.32"
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

  lazy val wsStream = Def.setting[Seq[ModuleID]] {
    Seq(
      (playWS % Provided).exclude("com.typesafe.akka", "*"),
      "com.typesafe.akka" %% "akka-stream" % Version.akka.value % Provided)
  }

  val playWS = ("com.typesafe.play" %% "play-ws-standalone" % Version.playWS).
    cross(CrossVersion.for3Use2_13).
    exclude("org.scala-lang.modules", "*")

  val playAhcWS = ("com.typesafe.play" %% "play-ahc-ws-standalone" % Version.playWS).cross(CrossVersion.for3Use2_13).exclude("org.scala-lang.modules", "*")

  val playWSJson = ("com.typesafe.play" %% "play-ws-standalone-json" % Version.playWS).cross(CrossVersion.for3Use2_13).exclude("org.scala-lang.modules", "*")

  val playWSXml = ("com.typesafe.play" %% "play-ws-standalone-xml" % Version.playWS).cross(CrossVersion.for3Use2_13)

  val slf4jApi = "org.slf4j" % "slf4j-api" % "1.7.36"
}

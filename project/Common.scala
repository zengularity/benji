import sbt.Keys._
import sbt._

import com.typesafe.tools.mima.plugin.MimaKeys._

object Common {
  import Dependencies.Version.{ akka => akkaVer }

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
      "-g:vars"),
    scalacOptions ++= {
      if (scalaVersion.value startsWith "2.12") {
        Seq(
          "-Ywarn-numeric-widen",
          "-Ywarn-infer-any",
          "-Ywarn-dead-code",
          "-Ywarn-unused",
          "-Ywarn-unused-import",
          "-Ywarn-value-discard",
          "-Yno-adapted-args",
          "-Ywarn-inaccessible",
          "-Ywarn-nullary-override",
          "-Ywarn-nullary-unit",
          "-Ywarn-extra-implicit")
      } else {
        Seq.empty
      }
    },
    libraryDependencies ++= {
      val silencerVer = "1.4.1"

      Seq(
        compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVer),
        "com.github.ghik" %% "silencer-lib" % silencerVer % Provided)
    },
    javacOptions in (Compile, compile) ++= Seq(
      "-source", "1.8", "-target", "1.8"),
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
    unmanagedSourceDirectories in Compile += {
      val base = (sourceDirectory in Compile).value

      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n >= 13 => base / "scala-2.13+"
        case _ => base / "scala-2.13-"
      }
    },
    fork in Test := true,
    mimaPreviousArtifacts := Set( /* organization.value %% name.value % previousRelease */ ),
    autoAPIMappings := true,
    apiMappings ++= mappings("org.scala-lang", "http://scala-lang.org/api/%s/")("scala-library").value,
    libraryDependencies ++= wsStream.value ++ Seq(
      "specs2-core", "specs2-junit").map(
        "org.specs2" %% _ % "4.7.0" % Test) ++ Seq(
          "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value,
          "com.typesafe.akka" %% "akka-stream-contrib" % "0.10",
          "ch.qos.logback" % "logback-classic" % "1.2.3").
          map(_ % Test)) ++ Wart.settings ++ Publish.settings

  lazy val wsStream = Def.setting[Seq[ModuleID]] {
    Seq(
      Dependencies.playWS % Provided,
      "com.typesafe.akka" %% "akka-stream" % akkaVer.value % Provided)
  }

  // ---

  def mappings(org: String, location: String, revision: String => String = identity)(names: String*) = Def.task[Map[File, URL]] {
    (for {
      entry: Attributed[File] <- (fullClasspath in Compile).value
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
    val akka = Def.setting[String] {
      if (scalaVersion.value startsWith "2.13.") "2.5.23"
      else "2.5.4" // upper 2.5.19 !! breaking akka-stream-contrib
    }

    val playWS = sys.env.getOrElse("WS_VERSION", "2.0.7") // upper 2.0.6

    val play: Def.Initialize[String] = {
      val playLower = "2.6.13"
      //val playUpper = "2.7.4"

      Def.setting[String] {
        sys.env.getOrElse("PLAY_VERSION", playLower)
      }
    }

    val playJson: Def.Initialize[String] = {
      Def.setting[String] {
        sys.env.getOrElse("PLAY_JSON_VERSION", play.value)
      }
    }
  }

  val playWS = "com.typesafe.play" %% "play-ws-standalone" % Version.playWS
  val playAhcWS = "com.typesafe.play" %% "play-ahc-ws-standalone" % Version.playWS
  val playWSJson = "com.typesafe.play" %% "play-ws-standalone-json" % Version.playWS
  val playWSXml = "com.typesafe.play" %% "play-ws-standalone-xml" % Version.playWS

  val slf4jApi = "org.slf4j" % "slf4j-api" % "1.7.26"
}

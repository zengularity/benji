import sbt.Keys._
import sbt._

import com.typesafe.tools.mima.plugin.MimaKeys._

object Common extends AutoPlugin {
  import Dependencies.Version.{ akka => akkaVer }

  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin

  override def projectSettings = Seq(
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
      val ver = scalaBinaryVersion.value

      if (ver == "2.12") {
        Seq(
          "-Xmax-classfile-name", "128",
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
      } else if (ver != "2.13") {
        Seq("-Xmax-classfile-name", "128")
      } else {
        Seq.empty
      }
    },
    resolvers += Resolver.sonatypeRepo("staging" /* releases */ ),
    libraryDependencies ++= {
      val silencerVer = "1.4.4"

      Seq(
        compilerPlugin(("com.github.ghik" %% "silencer-plugin" % silencerVer).
          cross(CrossVersion.full)),
        ("com.github.ghik" %% "silencer-lib" % silencerVer % Provided).
          cross(CrossVersion.full))
    },
    libraryDependencies ++= Dependencies.wsStream.value ++ Seq(
      "specs2-core", "specs2-junit").map(
        "org.specs2" %% _ % "4.9.3" % Test) ++ Seq(
          "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value,
          "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value,
          "ch.qos.logback" % "logback-classic" % "1.2.3").map(_ % Test),
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
    mimaFailOnNoPrevious in ThisBuild := false,
    mimaPreviousArtifacts := Set( /* organization.value %% name.value % previousRelease */ ),
    autoAPIMappings := true,
    apiMappings ++= mappings("org.scala-lang", "http://scala-lang.org/api/%s/")("scala-library").value) ++ Wart.settings ++ Publish.settings

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
    val playWS = sys.env.getOrElse("WS_VERSION", "2.0.8") // upper 2.0.6

    val play: Def.Initialize[String] = Def.setting[String] {
      val lower = {
        if (scalaBinaryVersion.value == "2.13") "2.7.3"
        else "2.6.13"
      }

      sys.env.getOrElse("PLAY_VERSION", lower)
    }

    val akka = Def.setting[String] {
      if (play.value startsWith "2.8.") "2.6.1"
      else "2.5.25"
    }

    val playJson: Def.Initialize[String] = Def.setting[String] {
      val lower = {
        if (scalaBinaryVersion.value == "2.13") "2.7.4"
        else "2.6.13"
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
    exclude("org.scala-lang.modules", "*")

  val playAhcWS = ("com.typesafe.play" %% "play-ahc-ws-standalone" % Version.playWS).exclude("org.scala-lang.modules", "*")

  val playWSJson = ("com.typesafe.play" %% "play-ws-standalone-json" % Version.playWS).exclude("org.scala-lang.modules", "*")

  val playWSXml = "com.typesafe.play" %% "play-ws-standalone-xml" % Version.playWS

  val slf4jApi = "org.slf4j" % "slf4j-api" % "1.7.30"
}

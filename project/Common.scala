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
      "-Yno-adapted-args",
      "-Ywarn-extra-implicit",
      "-Ywarn-inaccessible",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-g:vars"
    ),
    javacOptions in (Compile, compile) ++= Seq("-target", "1.8"),
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
    apiMappings ++= mappings("org.scala-lang", "http://scala-lang.org/api/%s/")("scala-library").value,
    libraryDependencies ++= wsStream ++ Seq(
      "specs2-core", "specs2-junit").map(
      "org.specs2" %% _ % "4.0.1" % Test) ++ Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer,
        "com.typesafe.akka" %% "akka-stream-contrib" % "0.8",
        "ch.qos.logback" % "logback-classic" % "1.1.7"
    ).map(_ % Test)
  ) ++ Scalariform.settings ++ Wart.settings

  val wsStream = Seq(
    Dependencies.playWS % Provided,
    "com.typesafe.akka" %% "akka-stream" % akkaVer % Provided)

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
    val playWS = "1.1.3"
  }

  val playWS = "com.typesafe.play" %% "play-ws-standalone" % Version.playWS
  val playAhcWS = "com.typesafe.play" %% "play-ahc-ws-standalone" % Version.playWS
  val playWSJson = "com.typesafe.play" %% "play-ws-standalone-json" % Version.playWS
  val playWSXml = "com.typesafe.play" %% "play-ws-standalone-xml" % Version.playWS

  val slf4jApi = "org.slf4j" % "slf4j-api" % "1.7.21"
}

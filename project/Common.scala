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
      "-Xfatal-warnings"
    ),
    scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq("-Xlint", "-g:vars")
      } else Seq.empty
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
          "-Ywarn-macros:after"
        )
      } else if (sv == "2.11") {
        Seq(
          "-Xmax-classfile-name",
          "128",
          "-Yopt:_",
          "-Ydead-code",
          "-Yclosure-elim",
          "-Yconst-opt"
        )
      } else if (sv == "2.13") {
        Seq(
          "-explaintypes",
          "-Werror",
          "-Wnumeric-widen",
          "-Wdead-code",
          "-Wvalue-discard",
          "-Wextra-implicit",
          "-Wmacros:after",
          "-Wunused",
          "-Wconf:cat=deprecation&msg=.*(fromFuture|ActorMaterializer).*:s"
        )
      } else {
        Seq(
          "-Wunused:all",
          "-language:implicitConversions",
          "-Wconf:cat=deprecation&msg=.*(fromFuture|ActorMaterializer).*:s"
        )
      }
    },
    Compile / console / scalacOptions ~= {
      _.filterNot(o =>
        (o.startsWith("-X") && o != "-Xmax-classfile-name") ||
          o.startsWith("-Y") || o.startsWith("-P:silencer")
      )
    },
    Compile / doc / scalacOptions ~= {
      _.filterNot { opt =>
        (opt.startsWith("-X") && opt != "-Xmax-classfile-name") ||
        opt.startsWith("-Y") || opt.startsWith("-P")
      }
    },
    Test / scalacOptions ~= {
      _.filterNot(_ == "-Xfatal-warnings")
    },
    Test / scalacOptions += "-Xlint:-infer-any", // specs2 `and`
    Test / scalacOptions ++= {
      if (scalaBinaryVersion.value startsWith "2.") {
        Seq("-Yrangepos")
      } else {
        Seq.empty
      }
    },
    resolvers ++= Resolver.sonatypeOssRepos("staging" /* releases */ ),
    libraryDependencies ++= {
      if (!scalaBinaryVersion.value.startsWith("3")) {
        val silencerVersion = "1.7.14"

        Seq(
          compilerPlugin(
            ("com.github.ghik" %% "silencer-plugin" % silencerVersion)
              .cross(CrossVersion.full)
          ),
          ("com.github.ghik" %% "silencer-lib" % silencerVersion % Provided)
            .cross(CrossVersion.full)
        )
      } else Seq.empty
    },
    libraryDependencies ++= Dependencies.wsStream.value ++ Seq(
      "specs2-core",
      "specs2-junit"
    ).map(d => {
      val v = {
        if (scalaBinaryVersion.value == "2.11") "4.10.6"
        else "4.15.0"
      }

      ("org.specs2" %% d % v) % Test
    }) ++ Seq(
      "com.typesafe.akka" %% "akka-stream-testkit" % akkaVer.value,
      "com.typesafe.akka" %% "akka-slf4j" % akkaVer.value,
      "ch.qos.logback" % "logback-classic" % "1.2.13"
    ).map(_ % Test),
    Compile / compile / javacOptions ++= Seq(
      "-source",
      "1.8",
      "-target",
      "1.8"
    ),
    Compile / unmanagedSourceDirectories += {
      val base = (Compile / sourceDirectory).value

      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, n)) if n < 13 => base / "scala-2.13-"
        case _                      => base / "scala-2.13+"
      }
    },
    Test / fork := true,
    ThisBuild / mimaFailOnNoPrevious := false,
    mimaPreviousArtifacts := Set(
      /* organization.value %% name.value % previousRelease */
    ),
    autoAPIMappings := true,
    apiMappings ++= mappings("org.scala-lang", "http://scala-lang.org/api/%s/")(
      "scala-library"
    ).value
  ) ++ Publish.settings

  // ---

  def mappings(
      org: String,
      location: String,
      revision: String => String = identity
    )(names: String*
    ) = Def.task[Map[File, URL]] {
    (for {
      entry: Attributed[File] <- (Compile / fullClasspath).value
      module: ModuleID <- entry.get(moduleID.key)
      if module.organization == org
      if names.exists(module.name.startsWith)
      rev = revision(module.revision)
    } yield entry.data -> url(location.format(rev)))(scala.collection.breakOut)
  }

  import scala.xml.{ Elem => XmlElem, Node => XmlNode }

  def transformPomDependencies(
      tx: XmlElem => Option[XmlNode]
    ): XmlNode => XmlNode = { node: XmlNode =>
    import scala.xml.{ NodeSeq, XML }
    import scala.xml.transform.{ RewriteRule, RuleTransformer }

    val tr = new RuleTransformer(new RewriteRule {
      override def transform(node: XmlNode): NodeSeq = node match {
        case e: XmlElem if e.label == "dependency" =>
          tx(e) match {
            case Some(n) => n
            case _       => NodeSeq.Empty
          }

        case _ => node
      }
    })

    tr.transform(node).headOption match {
      case Some(transformed) => transformed
      case _                 => sys.error("Fails to transform the POM")
    }
  }
}

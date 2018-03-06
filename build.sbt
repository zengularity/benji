import Common._

organization in ThisBuild := "com.zengularity"

scalaVersion in ThisBuild := "2.12.4"

crossScalaVersions in ThisBuild := Seq("2.11.11", scalaVersion.value)

lazy val core = project.in(file("core")).
  settings(Common.settings: _*).settings(
    name := "benji-core",
    libraryDependencies ++= Seq(
      "commons-codec" % "commons-codec" % "1.10",
      Dependencies.slf4jApi % Provided
    )
  )

val scalaXmlVer = Def.setting[String] {
  if (scalaVersion.value startsWith "2.11") "1.0.5"
  else "1.0.6"
}

val playVer = "2.6.1"

lazy val playTest = "com.typesafe.play" %% "play-test" % playVer % Test

lazy val s3 = project.in(file("s3")).
  settings(Common.settings: _*).settings(
    name := "benji-s3",
    libraryDependencies ++= Seq(
      Dependencies.playWSXml,
      Dependencies.playAhcWS,
      "org.scala-lang.modules" %% "scala-xml" % scalaXmlVer.value % Provided)
  ).dependsOn(core % "test->test;compile->compile")

lazy val google = project.in(file("google")).
  settings(Common.settings: _*).settings(
    name := "benji-google",
    libraryDependencies ++= Seq(
      Dependencies.playWSJson,
      Dependencies.playAhcWS,
      "com.google.apis" % "google-api-services-storage" % "v1-rev112-1.23.0")
  ).dependsOn(core % "test->test;compile->compile")

lazy val vfs = project.in(file("vfs")).
  settings(Common.settings: _*).settings(
    name := "benji-vfs",
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-vfs2" % "2.1",
      "com.typesafe.play" %% "play-json" % "2.6.7",
      Dependencies.slf4jApi,
      "commons-io" % "commons-io" % "2.4" % Test)
  ).dependsOn(core % "test->test;compile->compile")

lazy val play = project.in(file("play")).
  settings(Common.settings ++ Seq(
    name := "benji-play",
    libraryDependencies ++= Seq(
      Dependencies.playAhcWS,
      "com.typesafe.play" %% "play" % playVer % Provided,
      playTest),
    sources in (Compile, doc) := {
      val compiled = (sources in Compile).value

      compiled.filter { _.getName endsWith "NamedStorage.java" }
    }
  )).dependsOn(core % "test->test;compile->compile", vfs % "test->compile")

lazy val benji = (project in file(".")).
  enablePlugins(ScalaUnidocPlugin).
    settings(Seq(
      libraryDependencies ++= wsStream ++ Seq(Dependencies.playAhcWS),
      pomPostProcess := Common.transformPomDependencies { depSpec =>
        // Filter in POM the dependencies only required to compile sample in doc

        if ((depSpec \ "artifactId").text startsWith "benji-") {
          Some(depSpec)
        } else {
          Option.empty
        }
      },
      excludeFilter in doc := "play",
      scalacOptions ++= Seq("-Ywarn-unused-import", "-unchecked"),
      scalacOptions in (Compile, doc) ++= List(
        "-skip-packages", "highlightextractor"),
      unidocAllSources in (ScalaUnidoc, unidoc) ~= {
        _.map(_.filterNot { f =>
          val name = f.getName

          name.startsWith("NamedStorage") ||
          name.indexOf("-md-") != -1 ||
          (name.startsWith("package") &&
            f.getPath.indexOf("src_managed") != -1)
        })
      }
    ) ++ Publish.settings).
  dependsOn(s3, google, vfs, play).
  aggregate(core, s3, google, vfs, play)

publishTo in ThisBuild := Some {
  import Resolver.mavenStylePatterns

  val root = new java.io.File(".")

  Resolver.file("local-repo", root / "target" / "local-repo")
}

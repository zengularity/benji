import Common._

organization in ThisBuild := "com.zengularity"

scalaVersion in ThisBuild := "2.12.8"

crossScalaVersions in ThisBuild := Seq(
  "2.11.12", scalaVersion.value)

lazy val core = project.in(file("core")).
  settings(Common.settings: _*).settings(
    name := "benji-core",
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }

      Seq(
        x[ReversedMissingMethodProblem]("com.zengularity.benji.BucketRef#ListRequest.withPrefix"),
        x[ReversedMissingMethodProblem]("com.zengularity.benji.BucketVersioning#VersionedListRequest.withPrefix"))
    },
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" %% "scala-collection-compat" % "0.3.0",
      "commons-codec" % "commons-codec" % "1.10",
      Dependencies.slf4jApi % Provided
    )
  )

val scalaXmlVer = Def.setting[String] {
  if (scalaVersion.value startsWith "2.11") "1.0.5"
  else "1.0.6"
}

val playVer: Def.Initialize[String] = {
  val playLower = "2.6.13"
  //val playUpper = "2.7.0"

  Def.setting[String] {
    sys.env.getOrElse("PLAY_VERSION", playLower)
  }
}

lazy val playTest = Def.setting {
  "com.typesafe.play" %% "play-test" % playVer.value % Test
}

lazy val s3 = project.in(file("s3")).
  settings(Common.settings: _*).settings(
    name := "benji-s3",
    libraryDependencies ++= Seq(
      Dependencies.playWSXml,
      Dependencies.playAhcWS,
      "org.scala-lang.modules" %% "scala-xml" % scalaXmlVer.value % Provided),
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }

      val wasPrivate = Seq(
        x[MissingClassProblem]("com.zengularity.benji.s3.SignatureCalculator"),
        x[IncompatibleMethTypeProblem]("com.zengularity.benji.s3.VirtualHostWSRequestBuilder.this"),
        x[IncompatibleMethTypeProblem]("com.zengularity.benji.s3.PathStyleWSRequestBuilder.this"),
        x[IncompatibleMethTypeProblem]("com.zengularity.benji.s3.WSRequestBuilder.build"),
        x[MissingTypesProblem]("com.zengularity.benji.s3.WSS3BucketRef$ObjectVersions$"),
        x[IncompatibleResultTypeProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.<init>$default$2"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.apply"),
        x[IncompatibleResultTypeProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.apply$default$2"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.s3.WSS3BucketRef#Objects.copy"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.s3.WSS3BucketRef#Objects.this"),
        x[IncompatibleResultTypeProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.copy$default$2"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.copy"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.this"),
        x[MissingTypesProblem]("com.zengularity.benji.s3.WSS3BucketRef$Objects$"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.s3.WSS3BucketRef#Objects.apply")
      )

      wasPrivate
    }
  ).dependsOn(core % "test->test;compile->compile")

lazy val google = project.in(file("google")).
  settings(Common.settings: _*).settings(
    name := "benji-google",
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }

      val wasPrivate = Seq(
        x[MissingTypesProblem]("com.zengularity.benji.google.GoogleBucketRef$Objects$"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.google.GoogleBucketRef#Objects.apply"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.google.GoogleBucketRef#Objects.copy"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.google.GoogleBucketRef#Objects.this"),
        x[MissingTypesProblem]("com.zengularity.benji.google.GoogleBucketRef$ObjectsVersions$"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.google.GoogleBucketRef#ObjectsVersions.apply"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.google.GoogleBucketRef#ObjectsVersions.copy"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.google.GoogleBucketRef#ObjectsVersions.this")
      )

      wasPrivate
    },
    libraryDependencies ++= Seq(
      Dependencies.playWSJson,
      Dependencies.playAhcWS,
      "com.google.apis" % "google-api-services-storage" % "v1-rev20190129-1.28.0")
  ).dependsOn(core % "test->test;compile->compile")

lazy val vfs = project.in(file("vfs")).
  settings(Common.settings: _*).settings(
    name := "benji-vfs",
    mimaBinaryIssueFilters ++= {
      import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }

      val wasPrivate = Seq(
        x[IncompatibleResultTypeProblem]("com.zengularity.benji.vfs.VFSBucketRef.objects"),
        x[DirectMissingMethodProblem]("com.zengularity.benji.vfs.BenjiFileSelector.this")
      )

      wasPrivate ++ Seq(
        x[MissingClassProblem]("com.zengularity.benji.vfs.VFSBucketRef$objects$"))
    },
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-vfs2" % "2.3",
      "com.typesafe.play" %% "play-json" % playVer.value,
      Dependencies.slf4jApi,
      "commons-io" % "commons-io" % "2.4" % Test)
  ).dependsOn(core % "test->test;compile->compile")

lazy val play = project.in(file("play")).
  settings(Common.settings ++ Seq(
    name := "benji-play",
    version := {
      val ver = (version in ThisBuild).value
      val playMajor = playVer.value.split("\\.").take(2).mkString

      if (ver endsWith "-SNAPSHOT") {
        s"${ver.dropRight(9)}-play${playMajor}-SNAPSHOT"
      } else {
        s"${ver}-play${playMajor}"
      }
    },
    libraryDependencies ++= Seq(
      Dependencies.playAhcWS,
      "com.typesafe.play" %% "play" % playVer.value % Provided,
      playTest.value),
    unmanagedSourceDirectories in Test += {
      val v = playVer.value.split("\\.").take(2).mkString(".")

      baseDirectory.value / "src" / "test" / s"play-$v"
    },
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

  def localRepo = {
    val root = new java.io.File(".")
    root / "target" / "local-repo"
  }

  val repoDir = sys.env.get("REPO_PATH").map { path =>
    new java.io.File(path)
  }.getOrElse(localRepo)

  Resolver.file("repo", repoDir)
}

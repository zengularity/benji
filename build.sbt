organization in ThisBuild := "com.zengularity"

scalaVersion in ThisBuild := "2.12.8"

crossScalaVersions in ThisBuild := Seq(
  "2.11.12", scalaVersion.value, "2.13.0")

mimaFailOnNoPrevious in ThisBuild := false

lazy val core = project.in(file("core")).settings(
  name := "benji-core",
  scalacOptions in (Compile, compile) += { // Silencer
    "-P:silencer:globalFilters=constructor\\ deprecatedName\\ in\\ class\\ deprecatedName\\ is\\ deprecated"
  },
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }

    Seq(
      x[ReversedMissingMethodProblem]("com.zengularity.benji.BucketRef#ListRequest.withPrefix"),
      x[ReversedMissingMethodProblem]("com.zengularity.benji.BucketVersioning#VersionedListRequest.withPrefix"))
  },
  libraryDependencies ++= Dependencies.wsStream.value ++ Seq(
    "commons-codec" % "commons-codec" % "1.13",
    Dependencies.slf4jApi % Provided
  )
)

val scalaXmlVer = Def.setting[String] {
  val sv = scalaVersion.value

  if (sv startsWith "2.11.") "1.0.5"
  else if (sv startsWith "2.13.") "1.2.0"
  else "1.0.6"
}

import Dependencies.Version.{ play => playVer, playJson => playJsonVer }

lazy val playTest = Def.setting {
  ("com.typesafe.play" %% "play-test" % playVer.value % Test).
    exclude("com.typesafe.akka", "*")
}

lazy val s3 = project.in(file("s3")).settings(
  name := "benji-s3",
  libraryDependencies ++= Seq(
    Dependencies.playWSXml,
    Dependencies.playAhcWS,
    "org.scala-lang.modules" %% "scala-xml" % scalaXmlVer.value % Provided),
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }
    val pkg = "com.zengularity.benji.s3"

    val wasPrivate = Seq(
      x[MissingClassProblem](s"${pkg}.SignatureCalculator"),
      x[IncompatibleMethTypeProblem](s"${pkg}.VirtualHostWSRequestBuilder.this"),
      x[IncompatibleMethTypeProblem](s"${pkg}.PathStyleWSRequestBuilder.this"),
      x[IncompatibleMethTypeProblem](s"${pkg}.WSRequestBuilder.build"),
      x[MissingTypesProblem](s"${pkg}.WSS3BucketRef$$ObjectVersions$$"),
      x[IncompatibleResultTypeProblem](s"${pkg}.WSS3BucketRef#ObjectVersions.<init>$$default$$2"),
      x[DirectMissingMethodProblem](s"${pkg}.WSS3BucketRef#ObjectVersions.apply"),
      x[IncompatibleResultTypeProblem](s"${pkg}.WSS3BucketRef#ObjectVersions.apply$$default$$2"),
      x[DirectMissingMethodProblem](s"${pkg}.WSS3BucketRef#Objects.copy"),
      x[DirectMissingMethodProblem](s"${pkg}.WSS3BucketRef#Objects.this"),
      x[IncompatibleResultTypeProblem](s"${pkg}.WSS3BucketRef#ObjectVersions.copy$$default$$2"),
      x[DirectMissingMethodProblem](s"${pkg}.WSS3BucketRef#ObjectVersions.copy"),
      x[DirectMissingMethodProblem](s"${pkg}.WSS3BucketRef#ObjectVersions.this"),
      x[MissingTypesProblem](s"${pkg}.WSS3BucketRef$$Objects$$"),
      x[DirectMissingMethodProblem](s"${pkg}.WSS3BucketRef#Objects.apply"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.unapply"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.apply$default$1"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.<init>$default$1"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.s3.WSS3BucketRef#Objects.copy$default$1"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.s3.WSS3BucketRef#ObjectVersions.copy$default$1"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.s3.WSS3BucketRef#Objects.unapply")
    )

    wasPrivate
  }
).dependsOn(core % "test->test;compile->compile")

lazy val google = project.in(file("google")).settings(
  name := "benji-google",
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }
    val pkg = "com.zengularity.benji.google"

    val wasPrivate = Seq(
      x[MissingTypesProblem](s"${pkg}.GoogleBucketRef$$Objects$$"),
      x[DirectMissingMethodProblem](s"${pkg}.GoogleBucketRef#Objects.apply"),
      x[DirectMissingMethodProblem](s"${pkg}.GoogleBucketRef#Objects.copy"),
      x[DirectMissingMethodProblem](s"${pkg}.GoogleBucketRef#Objects.this"),
      x[MissingTypesProblem](s"${pkg}.GoogleBucketRef$$ObjectsVersions$$"),
      x[DirectMissingMethodProblem](s"${pkg}.GoogleBucketRef#ObjectsVersions.apply"),
      x[DirectMissingMethodProblem](s"${pkg}.GoogleBucketRef#ObjectsVersions.copy"),
      x[DirectMissingMethodProblem](s"${pkg}.GoogleBucketRef#ObjectsVersions.this"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.google.GoogleBucketRef#Objects.unapply"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.google.GoogleBucketRef#Objects.copy$default$1"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.google.GoogleBucketRef#ObjectsVersions.unapply"),
      x[IncompatibleSignatureProblem]("com.zengularity.benji.google.GoogleBucketRef#ObjectsVersions.copy$default$1")
    )

    wasPrivate
  },
  libraryDependencies ++= Seq(
    Dependencies.playWSJson,
    Dependencies.playAhcWS,
    "com.google.apis" % "google-api-services-storage" % "v1-rev20190129-1.28.0")
).dependsOn(core % "test->test;compile->compile")

lazy val vfs = project.in(file("vfs")).settings(
  name := "benji-vfs",
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }
    val pkg = "com.zengularity.benji.vfs"

    val wasPrivate = Seq(
      x[IncompatibleResultTypeProblem](s"$pkg.VFSBucketRef.objects"),
      x[DirectMissingMethodProblem](s"$pkg.BenjiFileSelector.this"),
      x[DirectMissingMethodProblem](s"$pkg.VFSBucketRef.objects")
    )

    wasPrivate ++ Seq(
      x[MissingClassProblem]("com.zengularity.benji.vfs.VFSBucketRef$objects$"))
  },
  libraryDependencies ++= Seq(
    "org.apache.commons" % "commons-vfs2" % "2.4.1",
    "com.typesafe.play" %% "play-json" % playJsonVer.value,
    Dependencies.slf4jApi,
    "commons-io" % "commons-io" % "2.6" % Test)
).dependsOn(core % "test->test;compile->compile")

lazy val play = project.in(file("play")).settings(
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
  libraryDependencies ++= {
    val playAhcWS = {
      if (scalaVersion.value startsWith "2.13.") {
        Seq(
          Dependencies.playAhcWS.
            exclude("org.scala-lang.modules", "scala-java8-compat_2.13"),
          "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.0")
      } else {
        Seq(Dependencies.playAhcWS)
      }
    }

    playAhcWS ++ Seq(
      "com.typesafe.play" %% "play" % playVer.value % Provided,
      playTest.value)
  },
  unmanagedSourceDirectories in Test += {
    val v = playVer.value.split("\\.").take(2).mkString(".")

    baseDirectory.value / "src" / "test" / s"play-$v"
  },
  sources in (Compile, doc) := {
    val compiled = (sources in Compile).value

    compiled.filter { _.getName endsWith "NamedStorage.java" }
  }
).dependsOn(core % "test->test;compile->compile", vfs % "test->compile")

lazy val benji = (project in file(".")).
  enablePlugins(ScalaUnidocPlugin).
    settings(Seq(
      libraryDependencies += Dependencies.playAhcWS,
      pomPostProcess := Common.transformPomDependencies { depSpec =>
        // Filter in POM the dependencies only required to compile sample in doc

        if ((depSpec \ "artifactId").text startsWith "benji-") {
          Some(depSpec)
        } else {
          Option.empty
        }
      },
      excludeFilter in doc := "play",
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

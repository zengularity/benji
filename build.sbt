ThisBuild / organization := "com.zengularity"

ThisBuild / scalaVersion := "2.12.15"

ThisBuild / crossScalaVersions := Seq(
  "2.11.12", scalaVersion.value, "2.13.6")

inThisBuild(
  List(
    //scalaVersion := "2.13.3",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalafixDependencies ++= Seq(
      "com.github.liancheng" %% "organize-imports" % "0.5.0")
  )
)

lazy val core = project.in(file("core")).settings(
  name := "benji-core",
  Compile / compile / scalacOptions ++= Seq(
    // Silencer
    "-P:silencer:globalFilters=constructor\\ deprecatedName\\ in\\ class\\ deprecatedName\\ is\\ deprecated",
    "-language:higherKinds"
  ),
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }

    Seq(
      x[ReversedMissingMethodProblem]("com.zengularity.benji.BucketRef#ListRequest.withPrefix"),
      x[ReversedMissingMethodProblem]("com.zengularity.benji.BucketVersioning#VersionedListRequest.withPrefix"))
  },
  libraryDependencies ++= Dependencies.wsStream.value ++ Seq(
    "commons-codec" % "commons-codec" % "1.15",
    Dependencies.slf4jApi % Provided
  )
)

val scalaXmlVer = Def.setting[String] {
  val sv = scalaBinaryVersion.value

  if (sv == "2.11") "1.0.5"
  else if (sv == "2.13") "1.2.0"
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
  mimaPreviousArtifacts := {
    if (scalaBinaryVersion.value == "2.12") {
      Set(organization.value %% moduleName.value % "2.1.0")
    } else {
      Set.empty[ModuleID]
    }
  },
  libraryDependencies ++= Seq(
    Dependencies.playWSJson,
    Dependencies.playAhcWS,
    "com.google.auth" % "google-auth-library-oauth2-http" % "1.2.0",
    "com.google.apis" % "google-api-services-storage" % "v1-rev20210127-1.31.0"
  )
).dependsOn(core % "test->test;compile->compile")

lazy val vfs = project.in(file("vfs")).settings(
  name := "benji-vfs",
  mimaBinaryIssueFilters ++= {
    import com.typesafe.tools.mima.core._, ProblemFilters.{ exclude => x }
    val pkg = "com.zengularity.benji.vfs"

    val wasPrivate = Seq(
      x[DirectMissingMethodProblem](s"$pkg.BenjiFileSelector.this"),
      x[DirectMissingMethodProblem](s"$pkg.VFSBucketRef.objects")
    )

    wasPrivate ++ Seq(
      x[MissingClassProblem]("com.zengularity.benji.vfs.VFSBucketRef$objects$"))
  },
  libraryDependencies ++= Seq(
    "org.apache.commons" % "commons-vfs2" % "2.9.0",
    "com.typesafe.play" %% "play-json" % playJsonVer.value,
    Dependencies.slf4jApi,
    "commons-io" % "commons-io" % "2.7" % Test)
).dependsOn(core % "test->test;compile->compile")

lazy val play = project.in(file("play")).settings(
  name := "benji-play",
  version := {
    val ver = (ThisBuild / version).value
    val playMajor = playVer.value.split("\\.").take(2).mkString

    if (ver endsWith "-SNAPSHOT") {
      s"${ver.dropRight(9)}-play${playMajor}-SNAPSHOT"
    } else {
      s"${ver}-play${playMajor}"
    }
  },
  mimaPreviousArtifacts := {
    val playMajor = playVer.value.split("\\.").take(2).mkString

    if (scalaBinaryVersion.value == "2.12") {
      Set(organization.value %% moduleName.value % s"2.0.5-play${playMajor}")
    } else {
      Set.empty[ModuleID]
    }
  },
  libraryDependencies ++= {
    val playAhcWS = {
      if (scalaBinaryVersion.value startsWith "2.13") {
        Seq(
          Dependencies.playAhcWS.
            exclude("org.scala-lang.modules", "scala-java8-compat_2.13"),
          "org.scala-lang.modules" %% "scala-java8-compat" % "0.9.1")
      } else {
        Seq(Dependencies.playAhcWS)
      }
    }

    playAhcWS ++ Seq(
      "com.typesafe.play" %% "play" % playVer.value % Provided,
      playTest.value)
  },
  Test / unmanagedSourceDirectories += {
    val v = playVer.value.split("\\.").take(2).mkString(".")

    baseDirectory.value / "src" / "test" / s"play-$v"
  },
  Compile / doc / sources := {
    val compiled = (Compile / sources).value

    compiled.filter { _.getName endsWith "NamedStorage.java" }
  }
).dependsOn(core % "test->test;compile->compile", vfs % "test->compile")

mimaPreviousArtifacts := Set.empty

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
      doc / excludeFilter := "play",
      Compile / doc / scalacOptions ++= List(
        "-skip-packages", "highlightextractor"),
      ScalaUnidoc / unidoc / unidocAllSources ~= {
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

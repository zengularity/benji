import sbt._
import sbt.Keys._

import xsbti.HashedVirtualFileRef

import sbtheader.HeaderPlugin.autoImport._

import com.typesafe.tools.mima.plugin.MimaKeys.{
  mimaFailOnNoPrevious,
  mimaPreviousArtifacts
}

object Publish {
  val siteUrl = "https://github.com/zengularity/benji"

  @inline def env(n: String): String = sys.env.getOrElse(n, n)

  lazy val repoName = env("PUBLISH_REPO_NAME")
  lazy val repoUrl = env("PUBLISH_REPO_URL")

  lazy val settings = Seq(
    mimaPreviousArtifacts := {
      if (scalaBinaryVersion.value == "2.12") {
        Set(organization.value %% moduleName.value % "2.0.5")
      } else {
        Set.empty[ModuleID]
      }
    },
    Compile / packageBin / mappings ~= coreFilter,
    Compile / packageSrc / mappings ~= coreFilter,
    licenses := Seq(License.Apache2),
    pomIncludeRepository := { _ => false },
    autoAPIMappings := true,
    apiURL := Some(url(siteUrl)), // TODO
    homepage := Some(url(siteUrl)),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zengularity/benji"),
        "git@github.com:zengularity/benji.git"
      )
    ),
    headerLicense := {
      val currentYear = java.time.Year.now(java.time.Clock.systemUTC).getValue
      Some(HeaderLicense.Custom(s"Copyright (C) 2018-$currentYear Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>"))
    },
    developers := List(
      Developer(
        id = "cchantep",
        name = "Cédric Chantepie",
        email = "",
        url = url("http://github.com/cchantep/")
      )
    ),
    publishTo := Some(repoUrl).map(repoName at _),
    credentials += Credentials(
      repoName,
      env("PUBLISH_REPO_ID"),
      env("PUBLISH_USER"),
      env("PUBLISH_PASS")
    )
  )

  private lazy val coreFilter: Seq[(HashedVirtualFileRef, String)] => Seq[(HashedVirtualFileRef, String)] = {
    (_: Seq[(HashedVirtualFileRef, String)]).filter {
      case (file, name) =>
        name.indexOf("com/github/ghik/silencer") == -1
    }
  }
}

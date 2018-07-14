import sbt._
import sbt.Keys._

import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._

import com.typesafe.tools.mima.plugin.MimaKeys.mimaPreviousArtifacts

object Publish {
  val siteUrl = "https://github.com/zengularity/benji"

  lazy val settings = Seq(
    resolvers += "Entrepot Releases" at "https://raw.github.com/zengularity/entrepot/master/releases",
    mimaPreviousArtifacts := Set(
      organization.value %% moduleName.value % "2.0.0"),
    licenses := Seq("Apache-2.0" ->
      url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    pomIncludeRepository := { _ => false },
    autoAPIMappings := true,
    apiURL := Some(url(siteUrl)), // TODO
    homepage := Some(url(siteUrl)),
    scmInfo := Some(
      ScmInfo(
        url("https://github.com/zengularity/benji"),
        "git@github.com:zengularity/benji.git")),
    headerLicense := {
      val currentYear = java.time.Year.now(java.time.Clock.systemUTC).getValue
      Some(HeaderLicense.Custom(
        s"Copyright (C) 2018-$currentYear Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>"))
    })
}

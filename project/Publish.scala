import sbt._
import sbt.Keys._

import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._

object Publish {
  val siteUrl = "http://www.zengularity.com"

  lazy val settings = Seq(
    licenses := Seq("Apache-2.0" ->
      url("https://www.apache.org/licenses/LICENSE-2.0.txt")),
    pomIncludeRepository := { _ => false },
    autoAPIMappings := true,
    apiURL := Some(url(siteUrl)), // TODO
    homepage := Some(url(siteUrl)),
    scmInfo := Some(
      ScmInfo(
        url("https://bitbucket.org/zengularity/zen-s3"),
        "scm:git@bitbucket.org:zengularity/zen-s3.git")),
    headerLicense := {
      val currentYear = java.time.Year.now(java.time.Clock.systemUTC).getValue
      Some(HeaderLicense.Custom(
        s"Copyright (C) 2018-$currentYear Zengularity SA (FaberNovel Technologies) <https://www.zengularity.com>"
      ))
    }
  )
}

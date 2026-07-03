ThisBuild / scalafmtOnCompile := true

// Scalafix
inThisBuild(
  List(
    // scalaVersion := "2.13.3",
    semanticdbVersion := scalafixSemanticdb.revision,
    scalafixDependencies += "io.github.cchantep" %% "offler-rules" % "1.0.1-SNAPSHOT"
  )
)

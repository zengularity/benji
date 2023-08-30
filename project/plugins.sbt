resolvers ++= Seq(
  "Tatami Releases" at "https://raw.githubusercontent.com/cchantep/tatami/master/releases",
  Resolver.typesafeIvyRepo("releases")
)

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.1")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.0")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")

addSbtPlugin("com.github.sbt" % "sbt-findbugs" % "2.0.0")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.2")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.8")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.0.1")

// For the the highlight extractor
libraryDependencies ++= Seq("commons-io" % "commons-io" % "2.13.0")

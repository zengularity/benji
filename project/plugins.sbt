resolvers ++= Seq(
  "Tatami Releases" at "https://raw.githubusercontent.com/cchantep/tatami/master/releases",
  Resolver.typesafeIvyRepo("releases")
)

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.10.0-RC1")

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.6")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.9.34")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.5.0")

addSbtPlugin("com.github.sbt" % "sbt-findbugs" % "2.0.0")

addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.20")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.0")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.6.5")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.8")

addSbtPlugin("com.dwijnand" % "sbt-dynver" % "4.1.1")

// For the the highlight extractor
libraryDependencies ++= Seq("commons-io" % "commons-io" % "2.11.0")

resolvers ++= Seq(
  "Tatami Releases" at "https://raw.githubusercontent.com/cchantep/tatami/master/releases",
  Resolver.typesafeIvyRepo("releases")
)

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.3")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.2")

addSbtPlugin("com.github.sbt" % "sbt-findbugs" % "2.0.0")

addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.3")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.5.0")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.2.0")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.7")

// For the the highlight extractor
libraryDependencies ++= Seq(
  "commons-io" % "commons-io" % "2.6")

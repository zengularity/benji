resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
)

resolvers ++= Seq(
  "Tatami Releases" at "https://raw.github.com/cchantep/tatami/master/releases")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.0")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.8.1")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.4.1")

addSbtPlugin("com.github.sbt" % "sbt-findbugs" % "2.0.0")

addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.2.1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "0.1.18")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.5")

// For the the highlight extractor
libraryDependencies ++= Seq(
  "commons-io" % "commons-io" % "2.4")

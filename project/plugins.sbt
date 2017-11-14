resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
)

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.8.2")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.7.1")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.3")

addSbtPlugin("de.johoop" % "findbugs4sbt" % "1.4.0")

// For the the highlight extractor
libraryDependencies += "commons-io" % "commons-io" % "2.4"

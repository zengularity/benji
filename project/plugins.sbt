resolvers ++= Seq(
  "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/"
)

//addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.5")

addSbtPlugin("org.scalariform" % "sbt-scalariform" % "1.5.0")

addSbtPlugin("com.eed3si9n" % "sbt-unidoc" % "0.3.3")

// For the the highlight extractor
libraryDependencies += "commons-io" % "commons-io" % "2.4"

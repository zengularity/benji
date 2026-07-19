resolvers ++= Seq(
  "Tatami Releases" at "https://raw.githubusercontent.com/cchantep/tatami/master/releases",
  Resolver.typesafeIvyRepo("releases"),
  "Sonatype Snapshots" at "https://central.sonatype.com/repository/maven-snapshots/"
)

addDependencyTreePlugin

addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")

addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")

addSbtPlugin("com.github.sbt" % "sbt-unidoc" % "0.6.1")

addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.6")

addSbtPlugin("com.github.sbt" % "sbt-header" % "5.11.0")

addSbtPlugin("cchantep" % "sbt-hl-compiler" % "0.12")

addSbtPlugin("com.github.sbt" % "sbt-dynver" % "5.1.1")

// For the the highlight extractor
libraryDependencies ++= Seq("commons-io" % "commons-io" % "2.22.0")

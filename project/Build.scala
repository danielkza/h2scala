import sbt._
import Keys._

object BuildSettings {
  val monocleVersion = "1.2.0-M1"
  val buildSettings = Defaults.defaultSettings ++ Seq(
    version := "0.1-SNAPSHOT",
    scalaVersion := "2.11.7",
    resolvers ++= Seq(
      Resolver.sonatypeRepo("releases"),
      Resolver.sonatypeRepo("snapshots")
    ),
    libraryDependencies ++= Seq(
      "org.scala-lang"             %  "scala-reflect"   % scalaVersion.value,
      "org.scalaz"                 %% "scalaz-core"     % "7.1.4",
      "com.chuusai"                %% "shapeless"       % "2.2.5",
      "com.typesafe.akka"          %% "akka-actor"      % "2.4.0",
      "org.specs2"                 %% "specs2-core"     % "3.6.4"        % "test"
    ),
    scalacOptions in Test ++= Seq("-Yrangepos"), // for Specs2
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1")
  )
}

object MyBuild extends Build {
  import BuildSettings._


  lazy val core: Project = Project("core", file("core"),
    settings = buildSettings) dependsOn(macros)

  lazy val root: Project = Project("http2-server", file("."),
    settings = buildSettings ++ Seq(run <<= run in Compile in core)
  ) aggregate(macros, core)

  lazy val macros: Project = Project("macros", file("macros"),
    settings = buildSettings ++ Seq(
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        "org.scala-lang" % "scala-compiler" % scalaVersion.value
      )
    )
  )

}

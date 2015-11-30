import sbt._
import Keys._

object BuildSettings {
  val Debug = config("debug").extend(Runtime)

  val buildSettings = Defaults.defaultSettings ++ Seq(
    version := "0.1-SNAPSHOT",
    scalaVersion := "2.11.7",

    javacOptions ++= Seq("-source", "1.8", "-target", "1.8"),
    scalacOptions ++= Seq("-target:jvm-1.8"),

    resolvers ++= Seq(
      Resolver.sonatypeRepo("releases"),
      Resolver.sonatypeRepo("snapshots")
    ),
    libraryDependencies ++= Seq(
      "org.scala-lang"             %  "scala-reflect"                    % scalaVersion.value,
      "org.scalaz"                 %% "scalaz-core"                      % "7.1.4",
      "com.chuusai"                %% "shapeless"                        % "2.2.5",
      "com.typesafe.akka"          %% "akka-actor"                       % "2.4.0",
      "com.typesafe.akka"          %% "akka-stream-experimental"         % "2.0-M1",
      "com.typesafe.akka"          %% "akka-http-core-experimental"      % "2.0-M1",
      "org.mortbay.jetty.alpn"      % "alpn-boot"                        % "8.1.6.v20151105" % "provided",
      "com.github.pathikrit"       %% "better-files"                     % "2.13.0" % "test",
      "org.specs2"                 %% "specs2-core"                      % "3.6.4"  % "test",
      "io.argonaut"                %% "argonaut"                         % "6.1-M4" % "test",
      "com.typesafe.akka"          %% "akka-stream-testkit-experimental" % "2.0-M1" % "test"

    ),
    addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.7.1"),

    fork in (run in Compile) := true,
    javaOptions ++= {
      val jars = (fullClasspath in Compile).value.files
      val alpnJar = jars.find(_.getName.contains("alpn-boot")).getOrElse {
        sys.error("No Jetty alpn-boot JAR found in classpath, cannot continue")
      }

      Seq(
        "-Xbootclasspath/p:" + alpnJar.absolutePath,
        "-Djavax.net.ssl.keyStore=" + (baseDirectory in ThisBuild).value / "keystore.jks",
        "-Djavax.net.ssl.keyStorePassword=test"
      )
    },
    javaOptions in Debug ++= Seq(
      "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=9999",
      "-Djavax.net.debug=handshake"
    ),

    scalacOptions in Test ++= Seq("-Yrangepos"), // for Specs2
    testOptions in Test += Tests.Setup { _ =>
      val casesPath = ((baseDirectory in ThisBuild) / "http2-frame-test-case").value.absolutePath
      sys.props += "http2.frame_tests_dir" -> casesPath
    }
  )
}

object MyBuild extends Build {
  import BuildSettings._

  lazy val core: Project =
    Project("core", file("core")).dependsOn(macros).settings(buildSettings)

  lazy val examples: Project =
    Project("examples", file("examples")).dependsOn(core)
      .configs(Debug)
      .settings(inConfig(Debug)(Defaults.configTasks):_*)
      .settings(buildSettings)
      .settings(
        libraryDependencies += "com.typesafe.akka"  %% "akka-http-experimental" % "2.0-M1"
      )

  lazy val root: Project =
    Project("http2-server", file(".")).aggregate(macros, core).settings(buildSettings).settings(
      run <<= run in Compile in core
    )

  lazy val macros: Project =
    Project("macros", file("macros")).settings(buildSettings).settings(
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect" % scalaVersion.value,
        "org.scala-lang" % "scala-compiler" % scalaVersion.value
      )
    )

}

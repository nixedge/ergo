import sbt.Keys.{licenses, _}
import sbt._

lazy val commonSettings = Seq(
  organization := "org.ergoplatform",
  name := "ergo",
  version := "2.1.2",
  scalaVersion := "2.12.8",
  resolvers ++= Seq("Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    "SonaType" at "https://oss.sonatype.org/content/groups/public",
    "Typesafe maven releases" at "http://repo.typesafe.com/typesafe/maven-releases/",
    "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"),
  homepage := Some(url("http://ergoplatform.org/")),
  licenses := Seq("CC0" -> url("https://creativecommons.org/publicdomain/zero/1.0/legalcode"))
)

val scorexVersion = "b58f225e-SNAPSHOT"
val sigmaStateVersion = "master-47514218-SNAPSHOT"
val ergoWalletVersion = "master-1e09a19c-SNAPSHOT"

// for testing current sigmastate build (see sigmastate-ergo-it jenkins job)
val effectiveSigmaStateVersion = Option(System.getenv().get("SIGMASTATE_VERSION")).getOrElse(sigmaStateVersion)

libraryDependencies ++= Seq(
  ("org.scorexfoundation" %% "sigma-state" % effectiveSigmaStateVersion).force()
    .exclude("ch.qos.logback", "logback-classic")
    .exclude("org.scorexfoundation", "scrypto"),
  "org.scala-lang.modules" %% "scala-async" % "0.9.7",
  ("org.scorexfoundation" %% "avl-iodb" % "0.2.15").exclude("ch.qos.logback", "logback-classic"),
  "org.scorexfoundation" %% "iodb" % "0.3.2",
  ("org.scorexfoundation" %% "scorex-core" % scorexVersion).exclude("ch.qos.logback", "logback-classic"),

  "org.ergoplatform" %% "ergo-wallet" % ergoWalletVersion,

  "org.typelevel" %% "cats-free" % "1.6.0",
  "javax.xml.bind" % "jaxb-api" % "2.+",
  "com.iheart" %% "ficus" % "1.4.+",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.google.guava" % "guava" % "21.0",
  "com.typesafe.akka" %% "akka-actor" % "2.5.+",
  "com.joefkelley" %% "argyle" % "1.0.0",

  "com.storm-enroute" %% "scalameter" % "0.8.+" % "test",
  "org.scalactic" %% "scalactic" % "3.0.+" % "test",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test,it",
  "org.scalacheck" %% "scalacheck" % "1.14.+" % "test",
  
  "org.scorexfoundation" %% "scorex-testkit" % scorexVersion % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.+" % "test",
  "com.typesafe.akka" %% "akka-http-testkit" % "10.1.+" % "test",
  "org.asynchttpclient" % "async-http-client" % "2.6.+" % "test",
  "com.fasterxml.jackson.dataformat" % "jackson-dataformat-properties" % "2.9.2" % "test",
  "com.spotify" % "docker-client" % "8.14.5" % "test" classifier "shaded"
)

coverageExcludedPackages := ".*ErgoApp.*;.*routes.*;.*ErgoPersistentModifier"

fork := true

val opts = Seq(
  "-server",
  // JVM memory tuning for 2g ram
  "-Xms128m",
  "-Xmx2G",
  //64M for stack, reduce after optimizations
  "-Xss64m",
  "-XX:+ExitOnOutOfMemoryError",
  // Java 9 support
  "-XX:+IgnoreUnrecognizedVMOptions",
  "--add-modules=java.xml.bind",

  // from https://groups.google.com/d/msg/akka-user/9s4Yl7aEz3E/zfxmdc0cGQAJ
  "-XX:+UseG1GC",
  "-XX:+UseNUMA",
  "-XX:+AlwaysPreTouch",

  // probably can't use these with jstack and others tools
  "-XX:+PerfDisableSharedMem",
  "-XX:+ParallelRefProcEnabled",
  "-XX:+UseStringDeduplication"
)

// -J prefix is required by the bash script
javaOptions in run ++= opts
scalacOptions ++= Seq("-Xfatal-warnings", "-feature", "-deprecation")

// set bytecode version to 8 to fix NoSuchMethodError for various ByteBuffer methods
// see https://github.com/eclipse/jetty.project/issues/3244
// these options applied only in "compile" task since scalac crashes on scaladoc compilation with "-release 8"
// see https://github.com/scala/community-builds/issues/796#issuecomment-423395500
scalacOptions in(Compile, compile) ++= Seq("-release", "8")

sourceGenerators in Compile += Def.task {
  val versionFile = (sourceManaged in Compile).value / "org" / "ergoplatform" / "Version.scala"
  val versionExtractor = """(\d+)\.(\d+)\.(\d+).*""".r
  val versionExtractor(major, minor, bugfix) = version.value
  IO.write(versionFile,
    s"""package org.ergoplatform
       |
       |object Version {
       |  val VersionString = "${version.value}"
       |  val VersionTuple = ($major, $minor, $bugfix)
       |}
       |""".stripMargin)
  Seq(versionFile)
}

mainClass in assembly := Some("org.ergoplatform.ErgoApp")

test in assembly := {}

assemblyJarName in assembly := s"ergo-${version.value}.jar"

assemblyMergeStrategy in assembly := {
  case "logback.xml" => MergeStrategy.first
  case "module-info.class" => MergeStrategy.discard
  case "reference.conf" => CustomMergeStrategy.concatReversed
  case other => (assemblyMergeStrategy in assembly).value(other)
}

enablePlugins(sbtdocker.DockerPlugin)
enablePlugins(JavaAppPackaging)

mappings in Universal += {
  val sampleFile = (resourceDirectory in Compile).value / "samples" / "local.conf.sample"
  sampleFile -> "conf/local.conf"
}

// removes all jar mappings in universal and appends the fat jar
mappings in Universal ++= {
  // universalMappings: Seq[(File,String)]
  val universalMappings = (mappings in Universal).value
  val fatJar = (assembly in Compile).value
  // removing means filtering
  val filtered = universalMappings filter {
    case (_, name) => !name.endsWith(".jar")
  }
  // add the fat jar
  filtered :+ (fatJar -> ("lib/" + fatJar.getName))
}

// add jvm parameter for typesafe config
bashScriptExtraDefines += """addJava "-Dconfig.file=${app_home}/../conf/local.conf""""

inConfig(Linux)(
  Seq(
    maintainer := "ergoplatform.org",
    packageSummary := "Ergo node",
    packageDescription := "Ergo node"
  )
)

Defaults.itSettings
configs(IntegrationTest extend Test)
inConfig(IntegrationTest)(Seq(
  parallelExecution := false,
  test := (test dependsOn docker).value,
))

dockerfile in docker := {
  val configTemplate = (resourceDirectory in IntegrationTest).value / "template.conf"
  val startErgo = (sourceDirectory in IntegrationTest).value / "container" / "start-ergo.sh"

  new Dockerfile {
    from("openjdk:9-jre-slim")
    label("ergo-integration-tests", "ergo-integration-tests")
    add(assembly.value, "/opt/ergo/ergo.jar")
    add(Seq(configTemplate, startErgo), "/opt/ergo/")
    run("chmod", "+x", "/opt/ergo/start-ergo.sh")
    entryPoint("/opt/ergo/start-ergo.sh")
  }
}

buildOptions in docker := BuildOptions(
  removeIntermediateContainers = BuildOptions.Remove.OnSuccess
)

//FindBugs settings

findbugsReportType := Some(FindbugsReport.Xml)
findbugsExcludeFilters := Some(scala.xml.XML.loadFile(baseDirectory.value / "findbugs-exclude.xml"))

//Scapegoat settings

scapegoatVersion in ThisBuild := "1.3.3"

scapegoatDisabledInspections := Seq("FinalModifierOnCaseClass")

Test / testOptions := Seq(Tests.Filter(s => !s.endsWith("Bench")))

lazy val ergo = (project in file(".")).settings(commonSettings: _*)

lazy val benchmarks = (project in file("benchmarks"))
  .settings(commonSettings, name := "ergo-benchmarks")
  .dependsOn(ergo % "test->test")
  .enablePlugins(JmhPlugin)

import org.jetbrains.sbtidea.{AutoJbr, JbrPlatform}
import org.jetbrains.sbtidea.packaging.artifact.DistBuilder
import scala.sys.process.Process

ThisBuild / scalaVersion       := "3.7.4"
ThisBuild / version            := "0.1.0-SNAPSHOT"
ThisBuild / intellijPluginName := "metallurgy"
ThisBuild / intellijBuild      := "261.26222.65"

Global / intellijAttachSources := true

addCommandAlias("fmt", "scalafmtAll")
addCommandAlias("check", "scalafmtCheckAll")
addCommandAlias("testHeadless", "test")
addCommandAlias("compilerTypeAcceptance", "testOnly com.hmemcpy.metallurgy.compilertype.*Test")

Global / javacOptions := Seq("--release", "17")

ThisBuild / resolvers ++= Seq(
  "JetBrains IntelliJ Repository" at "https://www.jetbrains.com/intellij-repository/releases",
  "JetBrains IntelliJ Dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"
)

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-language:implicitConversions",
  "-language:reflectiveCalls",
  "-language:existentials",
  "-language:unsafeNulls"
)

lazy val scalaPluginVersion           = "2026.1.20"
// revision of scala-library paired with the Scala 3.7.x toolchain
lazy val scala2LibraryVersion         = "2.13.16"
lazy val intellijTestFrameworkVersion = "261.26222.65"

lazy val intellijTestFrameworkDependencies = Seq(
  "com.jetbrains.intellij.platform" % "test-framework-core"         % intellijTestFrameworkVersion,
  "com.jetbrains.intellij.platform" % "test-framework-common"       % intellijTestFrameworkVersion,
  "com.jetbrains.intellij.platform" % "test-framework"              % intellijTestFrameworkVersion,
  "com.jetbrains.intellij.platform" % "test-framework-junit5"       % intellijTestFrameworkVersion,
  "com.jetbrains.intellij.java"     % "java-test-framework-shared"  % intellijTestFrameworkVersion,
  "com.jetbrains.intellij.java"     % "java-test-framework-backend" % intellijTestFrameworkVersion,
  "com.jetbrains.intellij.java"     % "java-test-framework"         % intellijTestFrameworkVersion
)

lazy val intellijPluginDependencies = Seq(
  "com.intellij.java".toPlugin,
  s"org.intellij.scala:$scalaPluginVersion".toPlugin
)

lazy val compileTestkit         = taskKey[Unit]("Compile the in-tree Scala plugin TestKit backport")
lazy val prepareIntellijTestSdk = taskKey[Unit]("Prepare SDK resources expected by IntelliJ light fixtures")

lazy val root =
  Project("metallurgy", file("."))
    .enablePlugins(SbtIdeaPlugin)
    .settings(
      name                      := "metallurgy",
      patchPluginXml            := pluginXmlOptions { xml =>
        xml.version = version.value
        xml.changeNotes = """<![CDATA[
          <b>Metallurgy</b> — pre-alpha.
          ]]>"""
      },
      libraryDependencies ++= Seq(
        ("org.scalameta"    % "mtags-interfaces"  % "1.3.4")
          .exclude("org.eclipse.lsp4j", "org.eclipse.lsp4j")
          .exclude("org.eclipse.lsp4j", "org.eclipse.lsp4j.jsonrpc"),
        "net.bytebuddy"     % "byte-buddy-agent" % "1.18.11",
        "junit"             % "junit"             % "4.13.2" % Test,
        "com.github.sbt"    % "junit-interface"   % "0.13.3" % Test,
        "org.junit.jupiter" % "junit-jupiter-api" % "5.13.0" % Test
      ) ++ intellijTestFrameworkDependencies.map(_ % Test),
      Test / javaOptions ++= Seq(
        s"-Didea.home.path=${intellijBaseDirectory.value}",
        "-Didea.is.unit.test=true",
        "-Didea.is.headless=true"
      ),
      Test / unmanagedClasspath +=
        Attributed.blank(baseDirectory.value / "testkit" / "target" / "scala-2.13" / "classes"),
      prepareIntellijTestSdk    := {
        updateIntellij.value
        val sdk    = intellijBaseDirectory.value
        val source = sdk / "plugins" / "java" / "lib" / "resources" / "jdkAnnotations.jar"
        val target = sdk / "lib" / "resources" / "jdkAnnotations.jar"
        if (!target.exists()) {
          IO.createDirectory(target.getParentFile)
          IO.copyFile(source, target)
        }
      },
      packageArtifact           := {
        val mappings        = packageMappings.value
        val outputDirectory = packageOutputDir.value
        val buildTarget     = target.value
        IO.delete(outputDirectory / "lib" / s"${intellijPluginName.value}.jar")
        IO.delete(buildTarget / "sbtidea.cache")
        new DistBuilder(streams.value, buildTarget).produceArtifact(mappings)
        outputDirectory
      },
      compileTestkit            := {
        prepareIntellijTestSdk.value
        val exitCode = Process(Seq("sbt", "--client", "compile"), baseDirectory.value / "testkit").!
        if (exitCode != 0) sys.error(s"TestKit compilation failed with exit code $exitCode")
      },
      Test / compile            := ((Test / compile) dependsOn compileTestkit).value,
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-s", "-a", "+c", "+q"),
      buildIntellijOptionsIndex := {},
      intellijPlugins           := intellijPluginDependencies,
      // the bundled Scala plugin supplies the Scala runtime from its own classloader at runtime,
      // so neither scala-library nor scala3-library is bundled here
      packageLibraryMappings := Seq(
        "org.scala-lang" % "scala-library"     % scala2LibraryVersion -> None,
        "org.scala-lang" % "scala3-library_3" % scalaVersion.value   -> None
      )
    )

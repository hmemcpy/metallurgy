import org.jetbrains.sbtidea.{AutoJbr, JbrPlatform}
import org.jetbrains.sbtidea.Keys._

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / intellijPluginName := "metallurgy"
ThisBuild / intellijBuild := "261"
ThisBuild / jbrInfo := AutoJbr(explicitPlatform = Some(JbrPlatform.osx_aarch64))

Global / intellijAttachSources := true

addCommandAlias("fmt", "scalafmtAll")
addCommandAlias("check", "scalafmtCheckAll")

Global / javacOptions := Seq("--release", "17")

ThisBuild / scalacOptions ++= Seq(
  "-explaintypes",
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xlint:serial",
  "-Ymacro-annotations",
  "-Ytasty-reader",
  "-Xfatal-warnings",
  "-language:implicitConversions",
  "-language:reflectiveCalls",
  "-language:existentials",
  "-Wconf:msg=legacy-binding:s"
)

lazy val scalaPluginVersion = "2026.1.20"

// The IntelliJ Platform test framework is no longer shipped as lib/testFramework.jar in the
// modular 261.x SDK distribution. It is published as separate Maven artifacts under the
// build number (261.26222.65) — see ADR 0005.
lazy val intellijTestFrameworkVersion = "261.26222.65"
lazy val intellijRepositoryReleases =
  "intellij-repository-releases" at "https://www.jetbrains.com/intellij-repository/releases"
lazy val intellijDependencies =
  "intellij-dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"

lazy val root =
  Project("metallurgy", file("."))
    .enablePlugins(SbtIdeaPlugin)
    .settings(
      name := "metallurgy",
      patchPluginXml := pluginXmlOptions { xml =>
        xml.version = version.value
        xml.changeNotes =
          """<![CDATA[
          <b>Metallurgy</b> — pre-alpha. Phase 0 scaffold only; no features enabled yet.
          ]]>"""
      },
      resolvers ++= Seq(intellijRepositoryReleases, intellijDependencies),
      libraryDependencies ++= Seq(
        "junit"             % "junit"             % "4.13.2"  % Test,
        "com.github.sbt"    % "junit-interface"   % "0.13.3"  % Test,
        "org.junit.jupiter" % "junit-jupiter-api" % "5.13.0"  % Test,
        // IntelliJ Platform test framework (split artifacts; see ADR 0005).
        "com.jetbrains.intellij.platform" % "test-framework-core"          % intellijTestFrameworkVersion % Test,
        "com.jetbrains.intellij.platform" % "test-framework-common"        % intellijTestFrameworkVersion % Test,
        "com.jetbrains.intellij.platform" % "test-framework"               % intellijTestFrameworkVersion % Test,
        "com.jetbrains.intellij.platform" % "test-framework-junit5"        % intellijTestFrameworkVersion % Test,
        "com.jetbrains.intellij.java"     % "java-test-framework-shared"   % intellijTestFrameworkVersion % Test,
        "com.jetbrains.intellij.java"     % "java-test-framework-backend"  % intellijTestFrameworkVersion % Test,
        "com.jetbrains.intellij.java"     % "java-test-framework"          % intellijTestFrameworkVersion % Test
      ),
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-s", "-a", "+c", "+q"),
      // We have no settings UI to index; skip the searchable-options builder (it fails
      // when the plugin has no configurables, and adds ~30s to packaging anyway).
      buildIntellijOptionsIndex := {},
      intellijPlugins := Seq(
        "com.intellij.java".toPlugin,
        s"org.intellij.scala:$scalaPluginVersion".toPlugin
      )
    )

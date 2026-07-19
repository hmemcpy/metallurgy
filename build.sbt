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
  "-Xfatal-warnings",
  "-language:implicitConversions",
  "-language:reflectiveCalls",
  "-language:existentials",
  "-Wconf:msg=legacy-binding:s"
)

lazy val scalaPluginVersion = "2026.1.20"

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
      libraryDependencies ++= Seq(
        "junit"             % "junit"             % "4.13.2"  % Test,
        "com.github.sbt"    % "junit-interface"   % "0.13.3"  % Test,
        "org.junit.jupiter" % "junit-jupiter-api" % "5.13.0"  % Test
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

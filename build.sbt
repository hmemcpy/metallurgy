import org.jetbrains.sbtidea.{AutoJbr, JbrPlatform}

ThisBuild / scalaVersion := "3.7.4"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / intellijPluginName := "metallurgy"
ThisBuild / intellijBuild := "261.26222.65"

Global / intellijAttachSources := true

addCommandAlias("fmt", "scalafmtAll")
addCommandAlias("check", "scalafmtCheckAll")

Global / javacOptions := Seq("--release", "17")

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xfatal-warnings",
  "-language:implicitConversions",
  "-language:reflectiveCalls",
  "-language:existentials",
  "-language:unsafeNulls"
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
          <b>Metallurgy</b> — pre-alpha.
          ]]>"""
      },
      libraryDependencies ++= Seq(
        "junit"             % "junit"             % "4.13.2"  % Test,
        "com.github.sbt"    % "junit-interface"   % "0.13.3"  % Test,
        "org.junit.jupiter" % "junit-jupiter-api" % "5.13.0"  % Test
      ),
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-s", "-a", "+c", "+q"),
      buildIntellijOptionsIndex := {},
      intellijPlugins := Seq(
        "com.intellij.java".toPlugin,
        s"org.intellij.scala:$scalaPluginVersion".toPlugin
      )
    )

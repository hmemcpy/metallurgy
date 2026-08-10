import java.util.zip.ZipFile
import java.nio.file.Files
import java.util.Properties

lazy val metallurgyBaseline = settingKey[Map[String, String]]("Exact Metallurgy toolchain and host baseline")

def loadMetallurgyBaseline(file: File): Map[String, String] = {
  val required = Set(
    "dogfood.sbt.version", "ide.probe.version", "intellij.build", "intellij.product.code", "intellij.release",
    "java.bytecode.release", "jbr.java.runtime.version", "jbr.java.vendor", "jbr.java.vendor.version", "sbt.version",
    "scala.compiler.version", "scala.plugin.id", "scala.plugin.version", "testkit.scala.version"
  )
  if (!file.isFile) sys.error(s"Metallurgy baseline manifest is missing: $file")
  val properties = new Properties
  val input      = Files.newInputStream(file.toPath)
  try properties.load(input)
  finally input.close()
  val actual = properties.stringPropertyNames().toArray(new Array[String](0)).toSet
  if (actual != required)
    sys.error(s"Metallurgy baseline keys differ: missing=${required -- actual}, extra=${actual -- required}")
  val values = required.iterator.map { name =>
    val value = properties.getProperty(name)
    if (value == null || value.isEmpty) sys.error(s"Metallurgy baseline value is missing or empty: $name")
    name -> value
  }.toMap
  try values("java.bytecode.release").toInt
  catch {
    case _: NumberFormatException => sys.error("Metallurgy baseline java.bytecode.release is not an integer")
  }
  values
}

ThisBuild / metallurgyBaseline := loadMetallurgyBaseline((ThisBuild / baseDirectory).value.getParentFile / "project" / "metallurgy-baseline.properties")

ThisBuild / scalaVersion := metallurgyBaseline.value.getOrElse("testkit.scala.version", sys.error("Metallurgy baseline key is missing: testkit.scala.version"))
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / resolvers ++= Seq(
  "JetBrains IntelliJ Repository" at "https://www.jetbrains.com/intellij-repository/releases",
  "JetBrains IntelliJ Dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"
)

ThisBuild / scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Xfatal-warnings")

lazy val ideProbeVersion = settingKey[String]("Pinned ide-probe version")
ThisBuild / ideProbeVersion := metallurgyBaseline.value.getOrElse("ide.probe.version", sys.error("Metallurgy baseline key is missing: ide.probe.version"))
lazy val prepareProbe261 = taskKey[File]("Build the IntelliJ 261 ide-probe plugin")
lazy val prepareMetallurgyPlugin = taskKey[File]("Archive the locally built Metallurgy plugin")
lazy val prepareScalaPlugin = taskKey[File]("Archive the pinned Scala plugin")

def intellijClasspath(home: File): Seq[File] = {
  val roots = Seq(
    home / "lib",
    home / "plugins" / "java" / "lib",
    home / "plugins" / "JUnit" / "lib"
  )
  roots.flatMap(root => (root ** "*.jar").get).distinct
}

def zipDirectory(root: File, output: File): Unit = {
  val mappings = (root ** "*").get.filter(_.isFile).map(file => file -> IO.relativize(root, file).get)
  IO.zip(mappings, output, Some(0L))
}

lazy val probe261 =
  Project("probe261", file("probe-261"))
    .settings(
      Compile / unmanagedJars ++= {
        val home = file(sys.env.getOrElse("METALLURGY_INTELLIJ_HOME", sys.error("METALLURGY_INTELLIJ_HOME is required")))
        val compilerShared = home / "custom-plugins" / "Scala" / "lib" / "compiler-shared.jar"
        val scala3Library  = home / "custom-plugins" / "Scala" / "lib" / "scala3-library_3.jar"
        if (!compilerShared.isFile)
          sys.error(s"Pinned Scala compiler event API is absent from $compilerShared")
        if (!scala3Library.isFile)
          sys.error(s"Pinned Scala compiler event API dependency is absent from $scala3Library")
        (intellijClasspath(home) ++ Seq(compilerShared, scala3Library)).map(Attributed.blank)
      },
      scalacOptions += "-Ytasty-reader",
      libraryDependencies ++= Seq(
        "org.virtuslab.ideprobe" %% "probe-plugin"   % ideProbeVersion.value,
        "junit"                  %  "junit"          % "4.13.2" % Test,
        "com.github.sbt"         %  "junit-interface" % "0.13.3" % Test
      )
    )

lazy val root =
  Project("ideprobe-tests", file("."))
    .aggregate(probe261)
    .settings(
      libraryDependencies ++= Seq(
        "org.virtuslab.ideprobe" %% "driver"         % ideProbeVersion.value % Test,
        "org.virtuslab.ideprobe" %% "junit-driver"   % ideProbeVersion.value % Test,
        "junit"                  %  "junit"          % "4.13.2"       % Test,
        "com.github.sbt"         % "junit-interface" % "0.13.3"       % Test
      ),
      prepareProbe261 := {
        val output       = target.value / "ideprobe-261.zip"
        val staging      = target.value / "ideprobe-261"
        val bundledProbe = (Test / update).value
          .select(moduleFilter("org.virtuslab.ideprobe", "driver_2.13", ideProbeVersion.value))
          .headOption
          .getOrElse(sys.error("ide-probe driver artifact is absent"))
        val patchJar     = (probe261 / Compile / packageBin).value
        IO.delete(staging)
        IO.createDirectory(staging)
        val driver       = new ZipFile(bundledProbe)
        try {
          val entry = Option(driver.getEntry(s"ideprobe_2.13-${ideProbeVersion.value}.zip"))
            .getOrElse(sys.error("bundled ide-probe plugin is absent"))
          val nested = staging / "ideprobe.zip"
          IO.transfer(driver.getInputStream(entry), nested)
          IO.unzip(nested, staging / "plugin")
        } finally driver.close()

        val scalaLibrary = staging / "plugin" / "ideprobe" / "lib" / "scala-library.jar"
        if (!scalaLibrary.isFile)
          sys.error("bundled ide-probe Scala library is absent")
        IO.delete(scalaLibrary)

        val pluginJar  = staging / "plugin" / "ideprobe" / "lib" / "probe-plugin.jar"
        val jarContent = staging / "probe-plugin"
        IO.unzip(pluginJar, jarContent)
        IO.unzip(patchJar, jarContent)
        val pluginXml  = jarContent / "META-INF" / "plugin.xml"
        val registration =
          """        <probeHandlerContributor implementation="org.virtuslab.ideprobe.MetallurgyStatusContributor"/>"""
        val originalDescriptor = IO.read(pluginXml)
        val extensionBoundary  = "    </extensions>\n\n</idea-plugin>"
        if (!originalDescriptor.contains(extensionBoundary))
          sys.error("ide-probe plugin descriptor has an unsupported extension layout")
        val optionalScalaDependency =
          """    <depends optional="true" config-file="scala-plugin.xml">org.intellij.scala</depends>"""
        if (!originalDescriptor.contains(optionalScalaDependency))
          sys.error("ide-probe optional Scala plugin dependency is absent")
        val descriptor              = originalDescriptor
          .replace(optionalScalaDependency, "    <depends>org.intellij.scala</depends>")
          .replace(extensionBoundary, s"$registration\n$extensionBoundary")
        IO.write(pluginXml, descriptor)
        IO.delete(pluginJar)
        zipDirectory(jarContent, pluginJar)
        IO.delete(output)
        zipDirectory(staging / "plugin", output)
        output
      },
      prepareMetallurgyPlugin := {
        val plugin = baseDirectory.value.getParentFile / "target" / "plugin" / "metallurgy"
        if (!(plugin / "lib" / "metallurgy.jar").isFile)
          sys.error("Metallurgy is not packaged; run packageArtifact in the repository root")
        val output   = target.value / "metallurgy.zip"
        val mappings = (plugin ** "*").get.filter(_.isFile).map { file =>
          file -> s"metallurgy/${IO.relativize(plugin, file).get}"
        }
        IO.delete(output)
        IO.zip(mappings, output, Some(0L))
        output
      },
      prepareScalaPlugin := {
        val home = file(sys.env.getOrElse("METALLURGY_INTELLIJ_HOME", sys.error("METALLURGY_INTELLIJ_HOME is required")))
        val plugin = home / "custom-plugins" / "Scala"
        if (!(plugin / "lib" / "scalaCommunity.jar").isFile)
          sys.error(s"Pinned Scala plugin is absent from $plugin")
        val output   = target.value / "scala-plugin.zip"
        val mappings = (plugin ** "*").get.filter(_.isFile).map { file =>
          file -> s"Scala/${IO.relativize(plugin, file).get}"
        }
        IO.delete(output)
        IO.zip(mappings, output, Some(0L))
        output
      },
      Test / test :=
        (Test / test).dependsOn(prepareProbe261, prepareMetallurgyPlugin, prepareScalaPlugin).value
    )

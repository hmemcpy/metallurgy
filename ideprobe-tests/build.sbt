import java.util.zip.ZipFile

ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / resolvers ++= Seq(
  "JetBrains IntelliJ Repository" at "https://www.jetbrains.com/intellij-repository/releases",
  "JetBrains IntelliJ Dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"
)

ThisBuild / scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Xfatal-warnings")

lazy val ideProbeVersion = "0.53.0"
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
        intellijClasspath(home).map(Attributed.blank)
      },
      libraryDependencies += "org.virtuslab.ideprobe" %% "probe-plugin" % ideProbeVersion
    )

lazy val root =
  Project("ideprobe-tests", file("."))
    .aggregate(probe261)
    .settings(
      libraryDependencies ++= Seq(
        "org.virtuslab.ideprobe" %% "driver"         % ideProbeVersion % Test,
        "org.virtuslab.ideprobe" %% "junit-driver"   % ideProbeVersion % Test,
        "junit"                  %  "junit"          % "4.13.2"       % Test,
        "com.github.sbt"         % "junit-interface" % "0.13.3"       % Test
      ),
      prepareProbe261 := {
        val output       = target.value / "ideprobe-261.zip"
        val staging      = target.value / "ideprobe-261"
        val bundledProbe = (Test / update).value
          .select(moduleFilter("org.virtuslab.ideprobe", "driver_2.13", ideProbeVersion))
          .headOption
          .getOrElse(sys.error("ide-probe driver artifact is absent"))
        val patchJar     = (probe261 / Compile / packageBin).value
        IO.delete(staging)
        IO.createDirectory(staging)
        val driver       = new ZipFile(bundledProbe)
        try {
          val entry = Option(driver.getEntry(s"ideprobe_2.13-$ideProbeVersion.zip"))
            .getOrElse(sys.error("bundled ide-probe plugin is absent"))
          val nested = staging / "ideprobe.zip"
          IO.transfer(driver.getInputStream(entry), nested)
          IO.unzip(nested, staging / "plugin")
        } finally driver.close()

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
        val descriptor         = originalDescriptor.replace(
          extensionBoundary,
          s"$registration\n$extensionBoundary"
        )
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

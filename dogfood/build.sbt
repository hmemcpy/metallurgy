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

ThisBuild / scalaVersion := metallurgyBaseline.value.getOrElse("scala.compiler.version", sys.error("Metallurgy baseline key is missing: scala.compiler.version"))
ThisBuild / scalacOptions ++= Seq("-deprecation", "-explain", "-feature", "-experimental")

lazy val root = (project in file("."))
  .settings(
    name := "metallurgy-dogfood",
    libraryDependencies ++= Seq(
      "io.circe"              %% "circe-generic" % "0.14.10",
      "dev.continuously.jing" %% "jing-openapi"  % "0.0.5",
      "io.getkyo"             %% "kyo-direct"    % "0.15.1",
      "dev.zio"               %% "zio-direct"    % "1.0.0-RC7"
    )
  )

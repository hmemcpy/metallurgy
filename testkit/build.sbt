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
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val intellijBuild = settingKey[String]("Pinned IntelliJ build")
ThisBuild / intellijBuild := metallurgyBaseline.value.getOrElse("intellij.build", sys.error("Metallurgy baseline key is missing: intellij.build"))
lazy val intellijSdkDirectory = Def.setting {
  sys.env.get("METALLURGY_INTELLIJ_HOME").map(file).getOrElse(
    file(sys.props("user.home")) / ".metallurgyPluginIC" / "sdk" / intellijBuild.value
  )
}

ThisBuild / resolvers ++= Seq(
  "JetBrains IntelliJ Repository" at "https://www.jetbrains.com/intellij-repository/releases",
  "JetBrains IntelliJ Dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"
)

scalacOptions ++= Seq(
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Ytasty-reader",
  "-Xfatal-warnings",
  "-language:implicitConversions",
  "-language:reflectiveCalls"
)

libraryDependencies ++= Seq(
  "junit"                              % "junit"                       % "4.13.2",
  "org.jetbrains"                      % "annotations"                 % "26.0.2",
  "com.jetbrains.intellij.platform"    % "test-framework-core"         % intellijBuild.value,
  "com.jetbrains.intellij.platform"    % "test-framework-common"       % intellijBuild.value,
  "com.jetbrains.intellij.platform"    % "test-framework"              % intellijBuild.value,
  "com.jetbrains.intellij.platform"    % "test-framework-junit5"       % intellijBuild.value,
  "com.jetbrains.intellij.java"        % "java-test-framework-shared"  % intellijBuild.value,
  "com.jetbrains.intellij.java"        % "java-test-framework-backend" % intellijBuild.value,
  "com.jetbrains.intellij.java"        % "java-test-framework"         % intellijBuild.value
)

Compile / unmanagedJars ++=
  Seq(
    intellijSdkDirectory.value / "lib",
    intellijSdkDirectory.value / "plugins" / "java" / "lib",
    intellijSdkDirectory.value / "plugins" / "Scala" / "lib"
  ).flatMap(directory => (directory ** "*.jar").classpath)

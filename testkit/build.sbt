ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val intellijBuild = "261.26222.65"
lazy val intellijSdkDirectory =
  file(sys.props("user.home")) / ".metallurgyPluginIC" / "sdk" / intellijBuild

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
  "com.jetbrains.intellij.platform"    % "test-framework-core"         % intellijBuild,
  "com.jetbrains.intellij.platform"    % "test-framework-common"       % intellijBuild,
  "com.jetbrains.intellij.platform"    % "test-framework"              % intellijBuild,
  "com.jetbrains.intellij.platform"    % "test-framework-junit5"       % intellijBuild,
  "com.jetbrains.intellij.java"        % "java-test-framework-shared"  % intellijBuild,
  "com.jetbrains.intellij.java"        % "java-test-framework-backend" % intellijBuild,
  "com.jetbrains.intellij.java"        % "java-test-framework"         % intellijBuild
)

Compile / unmanagedJars ++=
  Seq(
    intellijSdkDirectory / "lib",
    intellijSdkDirectory / "plugins" / "java" / "lib",
    intellijSdkDirectory / "plugins" / "Scala" / "lib"
  ).flatMap(directory => (directory ** "*.jar").classpath)

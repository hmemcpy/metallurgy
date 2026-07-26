ThisBuild / scalaVersion := "2.13.16"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / resolvers ++= Seq(
  "JetBrains IntelliJ Repository" at "https://www.jetbrains.com/intellij-repository/releases",
  "JetBrains IntelliJ Dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"
)

scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-Xfatal-warnings")

// ide-probe drives a real IDE with a probe server and exposes a HighlightInfo endpoint, so the
// producer's delivered PSI can be validated end-to-end (no ERROR severity, types present) without
// screen-watching. Slow: launches/downloads an IDE per run.
libraryDependencies ++= Seq(
  "org.virtuslab.ideprobe" %% "driver"         % "0.53.0" % Test,
  "org.virtuslab.ideprobe" %% "junit-driver"   % "0.53.0" % Test,
  "junit"                  %  "junit"          % "4.13.2" % Test,
  "com.github.sbt"         % "junit-interface" % "0.13.3" % Test
)

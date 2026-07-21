ThisBuild / scalaVersion := "3.5.2"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-explain", "-feature")
// Some libraries (kyo/jing/zio-direct) were built against a newer Scala 3 and would pull scala3-library
// 3.7.x by conflict resolution, breaking the 3.5.2 instance. Pin the Scala artifacts to the project version.
ThisBuild / dependencyOverrides ++= Seq(
  "org.scala-lang" %% "scala3-library"  % "3.5.2",
  "org.scala-lang" %% "scala3-compiler" % "3.5.2"
)

lazy val root = (project in file("."))
  .settings(
    name := "metallurgy-dogfood",
    libraryDependencies ++= Seq(
      "io.circe"              %% "circe-generic" % "0.14.10",
      "io.getkyo"             %% "kyo-direct"    % "0.15.1",
      "dev.zio"               %% "zio-direct"    % "1.0.0-RC7"
    )
  )

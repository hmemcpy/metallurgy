ThisBuild / scalaVersion := "3.7.4"
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

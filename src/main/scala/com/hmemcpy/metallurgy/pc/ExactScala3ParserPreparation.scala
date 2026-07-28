package com.hmemcpy.metallurgy.pc

private[metallurgy] object ExactScala3ParserPreparation:

  def open(scalaVersion: String): Either[String, Scala3ParserBridge] =
    Scala3CompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .left
      .map(errorMessage)
      .flatMap: artifacts =>
        Scala3ParserBridge
          .open(
            Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", scalaVersion),
            artifacts.map(_.toFile)
          )
          .left
          .map(openErrorMessage)

  private def errorMessage(error: Scala3CompilerResolutionError): String =
    Option(error.cause.getMessage).filter(_.nonEmpty).getOrElse(error.cause.getClass.getName)

  private def openErrorMessage(error: Scala3ParserOpenError): String =
    error match
      case Scala3ParserOpenError.InvalidArtifacts(message)              => message
      case Scala3ParserOpenError.InitializationFailed(_, message)       => message
      case Scala3ParserOpenError.MissingCapabilities(_, _, unavailable) =>
        unavailable.map(failure => s"${failure.capability}: ${failure.reason}").mkString("; ")

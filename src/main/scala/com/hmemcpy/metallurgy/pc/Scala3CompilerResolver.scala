package com.hmemcpy.metallurgy.pc

import coursierapi.{Dependency, Fetch, MavenRepository}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[pc] trait Scala3CompilerResolver:
  def resolve(scalaVersion: String): Either[Scala3CompilerResolutionError, Seq[Path]]

private[pc] object Scala3CompilerResolver:
  val publicCoursier: Scala3CompilerResolver = CoursierScala3CompilerResolver()

private[pc] trait Scala3CompilerArtifactFetcher:
  def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path]

private[pc] final class CoursierScala3CompilerResolver(
    artifactFetcher: Scala3CompilerArtifactFetcher,
    additionalRepositories: () => Seq[String]
) extends Scala3CompilerResolver:

  override def resolve(scalaVersion: String): Either[Scala3CompilerResolutionError, Seq[Path]] =
    try Right(artifactFetcher.fetch(scalaVersion, additionalRepositories()))
    catch case NonFatal(error) => Left(Scala3CompilerResolutionError(scalaVersion, error))

private[pc] object CoursierScala3CompilerResolver:
  def apply(): CoursierScala3CompilerResolver =
    new CoursierScala3CompilerResolver(
      CoursierScala3CompilerArtifactFetcher,
      () => CoursierPresentationCompilerResolver.repositoriesFrom(sys.props.get(RepositoryProperty))
    )

  private val RepositoryProperty = "metallurgy.pc.repositories"

private object CoursierScala3CompilerArtifactFetcher extends Scala3CompilerArtifactFetcher:
  override def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path] =
    val dependency   = Dependency.of("org.scala-lang", "scala3-compiler_3", scalaVersion)
    val repositories = additionalRepositories.map(MavenRepository.of)
    Fetch
      .create()
      .addDependencies(dependency)
      .addRepositories(repositories*)
      .fetch()
      .asScala
      .map(_.toPath)
      .toSeq

private[pc] final case class Scala3CompilerResolutionError(scalaVersion: String, cause: Throwable):
  def toException: RuntimeException =
    new RuntimeException(s"Could not resolve the exact Scala $scalaVersion compiler", cause)

package com.hmemcpy.metallurgy.pc

import coursierapi.{Dependency, Fetch, MavenRepository}

import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[pc] trait PresentationCompilerResolver:
  def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]]

private[pc] object PresentationCompilerResolver:
  val publicCoursier: PresentationCompilerResolver = CoursierPresentationCompilerResolver()

private[pc] trait PresentationCompilerArtifactFetcher:
  def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path]

private[pc] final class CoursierPresentationCompilerResolver(
    artifactFetcher: PresentationCompilerArtifactFetcher,
    additionalRepositories: () => Seq[String]
) extends PresentationCompilerResolver:

  override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
    try Right(artifactFetcher.fetch(scalaVersion, additionalRepositories()))
    catch case NonFatal(error) => Left(ArtifactResolutionError.DependencyResolution(scalaVersion, error))

private[pc] object CoursierPresentationCompilerResolver:
  def apply(): CoursierPresentationCompilerResolver =
    new CoursierPresentationCompilerResolver(
      CoursierPresentationCompilerArtifactFetcher,
      () => repositoriesFrom(sys.props.get(RepositoryProperty))
    )

  private val RepositoryProperty = "metallurgy.pc.repositories"

  private val StandardAdditionalRepositories = Seq(
    "https://central.sonatype.com/repository/maven-snapshots/",
    "https://repo.scala-lang.org/artifactory/maven-nightlies"
  )

  private[pc] def repositoriesFrom(configuredProperty: Option[String]): Seq[String] =
    val configured = configuredProperty.toSeq
      .flatMap(_.split(','))
      .map(_.trim)
      .filter(_.nonEmpty)
    (StandardAdditionalRepositories ++ configured).distinct

private object CoursierPresentationCompilerArtifactFetcher extends PresentationCompilerArtifactFetcher:
  override def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path] =
    val dependency   = Dependency.of("org.scala-lang", "scala3-presentation-compiler_3", scalaVersion)
    val repositories = additionalRepositories.map(MavenRepository.of)
    Fetch
      .create()
      .addDependencies(dependency)
      .addRepositories(repositories*)
      .fetch()
      .asScala
      .map(_.toPath)
      .toSeq

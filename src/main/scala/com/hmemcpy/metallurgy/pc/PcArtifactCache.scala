package com.hmemcpy.metallurgy.pc

import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[pc] final class PcArtifactCache private (root: Path):

  private val ManifestFileName = "manifest.txt"

  def validDirectory(scalaVersion: String): Option[Path] =
    val directory = directoryFor(scalaVersion)
    Option.when(validArtifactsIn(directory).isDefined)(directory)

  def validArtifacts(scalaVersion: String): Option[Seq[Path]] =
    validArtifactsIn(directoryFor(scalaVersion))

  def store(scalaVersion: String, artifacts: Seq[Path]): Either[ArtifactResolutionError, Path] =
    duplicateFileName(artifacts) match
      case Some(fileName) => Left(ArtifactResolutionError.DuplicateArtifact(fileName))
      case None           => writeArtifacts(directoryFor(scalaVersion), artifacts)

  private def writeArtifacts(
      directory: Path,
      artifacts: Seq[Path]
  ): Either[ArtifactResolutionError, Path] =
    try
      Files.createDirectories(directory)
      val entries  = artifacts
        .sortBy(_.getFileName.toString)
        .map: source =>
          val target = directory.resolve(source.getFileName.toString)
          Files.copy(source, target, REPLACE_EXISTING)
          ManifestEntry(target.getFileName.toString, sha256(target))
      val manifest = entries.map(entry => s"${entry.sha256} ${entry.fileName}").mkString("", "\n", "\n")
      Files.writeString(directory.resolve(ManifestFileName), manifest, StandardCharsets.UTF_8)
      Right(directory)
    catch case NonFatal(error) => Left(ArtifactResolutionError.CacheWrite(directory.toString, error))

  private def validArtifactsIn(directory: Path): Option[Seq[Path]] =
    val manifest = directory.resolve(ManifestFileName)
    if !Files.isRegularFile(manifest) then None
    else
      val parsed = Files
        .readAllLines(manifest, StandardCharsets.UTF_8)
        .asScala
        .toSeq
        .filter(_.nonEmpty)
        .map(parseEntry)
      Option
        .when(parsed.nonEmpty && parsed.forall(_.isDefined))(parsed.flatten)
        .filter(entries => entries.forall(entry => validEntry(directory, entry)))
        .map(_.map(entry => directory.resolve(entry.fileName)))

  private def parseEntry(line: String): Option[ManifestEntry] =
    line.split(" ", 2).toList match
      case checksum :: fileName :: Nil if Path.of(fileName).getFileName.toString == fileName =>
        Some(ManifestEntry(fileName, checksum))
      case _                                                                                 => None

  private def validEntry(directory: Path, entry: ManifestEntry): Boolean =
    val artifact = directory.resolve(entry.fileName)
    Files.isRegularFile(artifact) && MessageDigest.isEqual(
      entry.sha256.getBytes(StandardCharsets.US_ASCII),
      sha256(artifact).getBytes(StandardCharsets.US_ASCII)
    )

  private def duplicateFileName(artifacts: Seq[Path]): Option[String] =
    artifacts
      .groupBy(_.getFileName.toString)
      .collectFirst { case (fileName, matches) if matches.sizeCompare(1) > 0 => fileName }

  private def directoryFor(scalaVersion: String): Path =
    root.resolve(scalaVersion.replaceAll("[^a-zA-Z0-9._-]", "_"))

  private def sha256(path: Path): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val input  = Files.newInputStream(path)
    try
      val buffer = new Array[Byte](8192)
      Iterator
        .continually(input.read(buffer))
        .takeWhile(_ >= 0)
        .foreach(read => digest.update(buffer, 0, read))
    finally input.close()
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

private[pc] object PcArtifactCache:
  def apply(root: Path): PcArtifactCache = new PcArtifactCache(root)

private final case class ManifestEntry(fileName: String, sha256: String)

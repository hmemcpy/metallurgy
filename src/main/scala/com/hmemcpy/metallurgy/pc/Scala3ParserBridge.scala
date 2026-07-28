package com.hmemcpy.metallurgy.pc

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

private[metallurgy] trait Scala3ParserBridge extends AutoCloseable:
  def identity: Scala3ParserCompilerIdentity
  def capabilities: Scala3ParserCapabilities
  def loaderState: Scala3ParserLoaderState
  def parse(request: Scala3ParserRequest): Either[Scala3ParserError, ParserSyntaxSnapshot]
  override def close(): Unit

private[metallurgy] object Scala3ParserBridge:
  private val nextLoaderId = new AtomicLong(0L)

  def open(
      coordinate: Scala3ParserArtifactCoordinate,
      artifacts: Seq[File]
  ): Either[Scala3ParserOpenError, Scala3ParserBridge] =
    if artifacts.isEmpty then Left(Scala3ParserOpenError.InvalidArtifacts("the exact compiler artifact set is empty"))
    else if artifacts.exists(file => !file.isFile || !file.canRead) then
      Left(Scala3ParserOpenError.InvalidArtifacts("every exact compiler artifact must be a readable file"))
    else
      try
        val identity = Scala3ParserCompilerIdentity(
          coordinate,
          artifacts.toVector.zipWithIndex.map((artifact, ordinal) => artifactIdentity(artifact, ordinal)),
          Scala3ParserLoaderIdentity(nextLoaderId.incrementAndGet())
        )
        StructuralScala3ParserBridge.open(identity, artifacts)
      catch
        case NonFatal(error) =>
          Left(
            Scala3ParserOpenError.InvalidArtifacts(
              Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)
            )
          )

  private def artifactIdentity(file: File, ordinal: Int): Scala3ParserArtifactIdentity =
    Scala3ParserArtifactIdentity(
      file.getName,
      file.getCanonicalPath,
      file.length(),
      digest(file),
      ordinal
    )

  private def digest(file: File): String =
    val input  = Files.newInputStream(file.toPath)
    val hash   = MessageDigest.getInstance("SHA-256")
    val buffer = new Array[Byte](64 * 1024)
    try
      var count = input.read(buffer)
      while count >= 0 do
        if count > 0 then hash.update(buffer, 0, count)
        count = input.read(buffer)
      hash.digest().map(byte => f"${byte & 0xff}%02x").mkString
    finally input.close()

private[metallurgy] final case class Scala3ParserArtifactCoordinate(
    organization: String,
    artifact: String,
    version: String
):
  require(organization.nonEmpty, "artifact organization must be non-empty")
  require(artifact.nonEmpty, "artifact name must be non-empty")
  require(version.nonEmpty, "artifact version must be non-empty")

private[metallurgy] final case class Scala3ParserArtifactIdentity(
    fileName: String,
    canonicalPath: String,
    byteSize: Long,
    sha256: String,
    ordinal: Int = 0
)

private[metallurgy] opaque type Scala3ParserLoaderIdentity = Long

private[metallurgy] object Scala3ParserLoaderIdentity:
  def apply(value: Long): Scala3ParserLoaderIdentity =
    require(value > 0, s"loader identity must be positive: $value")
    value

  extension (identity: Scala3ParserLoaderIdentity) def value: Long = identity

private[metallurgy] final case class Scala3ParserCompilerIdentity(
    coordinate: Scala3ParserArtifactCoordinate,
    artifacts: Vector[Scala3ParserArtifactIdentity],
    loader: Scala3ParserLoaderIdentity
)

private[metallurgy] enum Scala3ParserLoaderState:
  case Open, Closed

private[metallurgy] final case class Scala3ParserRequest(
    sourceUri: ParserSourceUri,
    sourceText: String,
    compilerOptions: Vector[String],
    cancellation: Scala3ParserCancellation = Scala3ParserCancellation.Never
)

private[metallurgy] opaque type ParserSourceUri = String

private[metallurgy] object ParserSourceUri:
  def from(value: String): Either[String, ParserSourceUri] =
    Either.cond(value.nonEmpty, value, "parser source URI must be non-empty")

  extension (sourceUri: ParserSourceUri) def value: String = sourceUri

private[metallurgy] trait Scala3ParserCancellation:
  def checkCanceled(): Unit

private[metallurgy] object Scala3ParserCancellation:
  case object Never extends Scala3ParserCancellation:
    override def checkCanceled(): Unit = ()

private[metallurgy] final case class ParserSyntaxSnapshot(
    sourceUri: ParserSourceUri,
    sourceText: String,
    sourceDigest: String,
    sourceLength: Int,
    compilerOptions: Vector[String],
    rootNodeId: Long,
    nodes: Vector[ParserSyntaxNode],
    positioned: Vector[ParserPositionedSyntax],
    comments: Vector[ParserComment],
    diagnostics: Vector[ParserDiagnostic],
    capabilities: Scala3ParserCapabilities,
    compilerIdentity: Scala3ParserCompilerIdentity
)

private[metallurgy] final case class ParserPositionedSyntax(
    id: Long,
    production: String,
    fields: Vector[ParserSyntaxField],
    position: ParserNodePosition,
    occurrences: Vector[ParserPositionedOccurrence]
)

private[metallurgy] enum ParserFieldPathSegment:
  case NamedField(name: String)
  case OptionalNesting
  case RepeatedIndex(index: Int)
  case NestedProductBoundary(production: String)

private[metallurgy] final case class ParserNodeOccurrence(
    ownerNodeId: Long,
    fieldPath: Vector[ParserFieldPathSegment]
)

private[metallurgy] final case class ParserPositionedOccurrence(
    ownerNodeId: Long,
    fieldPath: Vector[ParserFieldPathSegment]
)

private[metallurgy] final case class ParserComment(
    range: PcSourceRange,
    raw: String,
    kind: ParserCommentKind
)

private[metallurgy] enum ParserCommentKind:
  case Line, Block, Doc

private[metallurgy] object ParserSyntaxSnapshot:
  def digest(sourceText: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(sourceText.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  def evidenceFingerprint(snapshot: ParserSyntaxSnapshot): String =
    val artifacts = snapshot.compilerIdentity.artifacts.map: artifact =>
      (artifact.ordinal, artifact.fileName, artifact.byteSize, artifact.sha256)
    digest(
      Vector(
        snapshot.sourceUri.value,
        snapshot.sourceDigest,
        snapshot.sourceLength,
        snapshot.compilerOptions,
        snapshot.rootNodeId,
        artifacts,
        snapshot.nodes,
        snapshot.positioned,
        snapshot.comments,
        snapshot.diagnostics,
        snapshot.capabilities,
        snapshot.compilerIdentity.coordinate
      ).mkString("\u001e")
    )

private[metallurgy] final case class ParserSyntaxNode(
    id: Long,
    production: String,
    fields: Vector[ParserSyntaxField],
    position: ParserNodePosition,
    occurrences: Vector[ParserNodeOccurrence]
)

private[metallurgy] final case class ParserSyntaxField(name: String, value: ParserFieldValue)

private[metallurgy] enum ParserFieldValue:
  case Node(nodeId: Long)
  case Positioned(id: Long)
  case Optional(value: Option[ParserFieldValue])
  case Repeated(values: Vector[ParserFieldValue])
  case Product(production: String, fields: Vector[ParserSyntaxField])
  case Name(value: String)
  case GeneratedName(base: String, separator: String, generationIndex: Int)
  case Scalar(value: ParserScalar)
  case Unsupported(runtimeType: String)

private[metallurgy] enum ParserScalar:
  case Text(value: String)
  case Integer(value: Int)
  case LongInteger(value: Long)
  case Decimal(value: Double)
  case Logical(value: Boolean)
  case Character(value: Char)
  case UnitValue

private[metallurgy] enum ParserNodePosition:
  case Absent
  case Positioned(range: PcSourceRange, point: Int, provenance: ParserPositionProvenance)

private[metallurgy] enum ParserPositionProvenance:
  case SourceDerived, Synthetic

private[metallurgy] final case class ParserDiagnostic(
    severity: ParserDiagnosticSeverity,
    message: String,
    position: Option[ParserDiagnosticPosition]
)

private[metallurgy] final case class ParserDiagnosticPosition(range: PcSourceRange, point: Int)

private[metallurgy] enum ParserDiagnosticSeverity:
  case Error, Warning, Information

private[metallurgy] final case class Scala3ParserCapabilities(
    publishedParser: ParserCapabilityStatus,
    contextSetup: ParserCapabilityStatus,
    sourceConstruction: ParserCapabilityStatus,
    parserConstruction: ParserCapabilityStatus,
    productTraversal: ParserCapabilityStatus,
    sourcePositions: ParserCapabilityStatus,
    diagnostics: ParserCapabilityStatus,
    positionedSyntax: ParserCapabilityStatus,
    comments: ParserCapabilityStatus
):
  def requiredUnavailable: Vector[ParserCapabilityFailure] =
    Vector(
      "context setup"       -> contextSetup,
      "source construction" -> sourceConstruction,
      "parser construction" -> parserConstruction,
      "product traversal"   -> productTraversal,
      "source positions"    -> sourcePositions,
      "diagnostics"         -> diagnostics,
      "positioned syntax"   -> positionedSyntax,
      "comments"            -> comments
    ).collect { case (name, ParserCapabilityStatus.Unavailable(reason)) =>
      ParserCapabilityFailure(name, reason)
    }

private[metallurgy] enum ParserCapabilityStatus:
  case Available
  case Unavailable(reason: String)

private[metallurgy] final case class ParserCapabilityFailure(capability: String, reason: String)

private[metallurgy] enum Scala3ParserOpenError:
  case InvalidArtifacts(message: String)
  case MissingCapabilities(
      identity: Scala3ParserCompilerIdentity,
      capabilities: Scala3ParserCapabilities,
      failures: Vector[ParserCapabilityFailure]
  )
  case InitializationFailed(identity: Scala3ParserCompilerIdentity, message: String)

private[metallurgy] enum Scala3ParserError:
  case Closed
  case SetupRejected(message: String)
  case ParseFailed(message: String)

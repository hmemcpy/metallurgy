package com.hmemcpy.metallurgy.pc

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
    compilerIdentity: Scala3ParserCompilerIdentity,
    endMarkers: Vector[ParserEndMarker],
    runtimeSupplements: Vector[ParserRuntimeSupplement] = Vector.empty,
    attachments: Vector[ParserTreeAttachment] = Vector.empty,
    scannerTokens: Vector[ParserScannerToken] = Vector.empty
)

private[metallurgy] final case class ParserEndMarker(ownerNodeId: Long, designatorRange: PcSourceRange)

private[metallurgy] final case class ParserScannerToken(
    ordinal: Int,
    tokenId: Int,
    runtimeKind: String,
    kind: ParserScannerTokenKind,
    range: PcSourceRange,
    point: Int,
    provenance: ParserPositionProvenance
)

private[metallurgy] enum ParserScannerTokenKind:
  case Dot, Hash, LeftParenthesis, RightParenthesis, TypeKeyword, Literal, Other

private[metallurgy] final case class ParserRuntimeSupplement(
    ownerNodeId: Long,
    fields: Vector[ParserSyntaxField]
)

private[metallurgy] final case class ParserTreeAttachment(
    ownerNodeId: Long,
    ordinal: Int,
    keyKind: String,
    value: ParserAttachmentValue
)

private[metallurgy] enum ParserAttachmentValue:
  case Product(production: String)
  case Name(value: String)
  case Scalar(value: ParserScalar)
  case RuntimeKind(kind: String)

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
    CanonicalByteEncoder.sha256Hex(CanonicalByteEncoder.encodeSnapshot(snapshot))

  def scannerEvidenceFingerprint(snapshot: ParserSyntaxSnapshot): String =
    val encoder = CanonicalByteEncoder()
    encoder.tag(1)
    encoder.sequence(snapshot.scannerTokens): token =>
      encoder.int(token.ordinal)
      encoder.int(token.tokenId)
      encoder.string(token.runtimeKind)
      encoder.tag(token.kind.ordinal)
      encoder.int(token.range.startOffset)
      encoder.int(token.range.endOffset)
      encoder.int(token.point)
      encoder.tag(token.provenance.ordinal)
    CanonicalByteEncoder.sha256Hex(encoder.result())

private[metallurgy] final class CanonicalByteEncoder private ():
  private val bytes = new ByteArrayOutputStream()
  private val out   = new DataOutputStream(bytes)

  def tag(value: Int): Unit                                        = out.writeByte(value)
  def int(value: Int): Unit                                        = out.writeInt(value)
  def long(value: Long): Unit                                      = out.writeLong(value)
  def double(value: Double): Unit                                  = out.writeLong(java.lang.Double.doubleToRawLongBits(value))
  def boolean(value: Boolean): Unit                                = out.writeBoolean(value)
  def char(value: Char): Unit                                      = out.writeChar(value.toInt)
  def string(value: String): Unit                                  =
    int(value.length)
    value.foreach(char)
  def sequence[A](values: IterableOnce[A])(write: A => Unit): Unit =
    val strict = values.iterator.toVector
    int(strict.length)
    strict.foreach(write)
  def result(): Array[Byte]                                        =
    out.flush()
    bytes.toByteArray

private[metallurgy] object CanonicalByteEncoder:
  def apply(): CanonicalByteEncoder = new CanonicalByteEncoder()

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString

  def encodeSnapshot(snapshot: ParserSyntaxSnapshot): Array[Byte] =
    val e            = apply()
    e.tag(1)
    e.string(snapshot.sourceUri.value)
    e.string(snapshot.sourceText)
    e.string(snapshot.sourceDigest)
    e.int(snapshot.sourceLength)
    e.sequence(snapshot.compilerOptions)(e.string)
    e.long(snapshot.rootNodeId)
    e.sequence(snapshot.compilerIdentity.artifacts): artifact =>
      e.int(artifact.ordinal); e.string(artifact.fileName); e.long(artifact.byteSize); e.string(artifact.sha256)
    val coordinate   = snapshot.compilerIdentity.coordinate
    e.string(coordinate.organization); e.string(coordinate.artifact); e.string(coordinate.version)
    e.sequence(snapshot.nodes)(node =>
      writeSyntax(node.id, node.production, node.fields, node.position, node.occurrences, e)
    )
    e.sequence(snapshot.positioned): value =>
      e.long(value.id); e.string(value.production); e.sequence(value.fields)(writeField(_, e));
      writePosition(value.position, e)
      e.sequence(value.occurrences)(o => { e.long(o.ownerNodeId); writePath(o.fieldPath, e) })
    e.sequence(snapshot.comments): comment =>
      writeRange(comment.range, e); e.string(comment.raw); e.tag(comment.kind.ordinal)
    e.sequence(snapshot.diagnostics): diagnostic =>
      e.tag(diagnostic.severity.ordinal); e.string(diagnostic.message)
      diagnostic.position match
        case None           => e.tag(0)
        case Some(position) => e.tag(1); writeRange(position.range, e); e.int(position.point)
    if snapshot.endMarkers.nonEmpty then
      e.tag(10)
      e.sequence(snapshot.endMarkers): marker =>
        e.long(marker.ownerNodeId); writeRange(marker.designatorRange, e)
    val capabilities = snapshot.capabilities
    Vector(
      capabilities.publishedParser,
      capabilities.contextSetup,
      capabilities.sourceConstruction,
      capabilities.parserConstruction,
      capabilities.productTraversal,
      capabilities.sourcePositions,
      capabilities.diagnostics,
      capabilities.positionedSyntax,
      capabilities.comments
    ).foreach(writeCapability(_, e))
    if snapshot.runtimeSupplements.nonEmpty then
      e.tag(11)
      e.sequence(snapshot.runtimeSupplements): supplement =>
        e.long(supplement.ownerNodeId)
        e.sequence(supplement.fields)(writeField(_, e))
    if snapshot.attachments.nonEmpty then
      e.tag(12)
      e.sequence(snapshot.attachments): attachment =>
        e.long(attachment.ownerNodeId)
        e.int(attachment.ordinal)
        e.string(attachment.keyKind)
        attachment.value match
          case ParserAttachmentValue.Product(production) => e.tag(1); e.string(production)
          case ParserAttachmentValue.Name(value)         => e.tag(2); e.string(value)
          case ParserAttachmentValue.Scalar(value)       => e.tag(3); writeScalar(value, e)
          case ParserAttachmentValue.RuntimeKind(kind)   => e.tag(4); e.string(kind)
    e.result()

  private def writeSyntax(
      id: Long,
      production: String,
      fields: Vector[ParserSyntaxField],
      position: ParserNodePosition,
      occurrences: Vector[ParserNodeOccurrence],
      e: CanonicalByteEncoder
  ): Unit =
    e.long(id); e.string(production); e.sequence(fields)(writeField(_, e)); writePosition(position, e)
    e.sequence(occurrences)(o => { e.long(o.ownerNodeId); writePath(o.fieldPath, e) })

  private def writeField(field: ParserSyntaxField, e: CanonicalByteEncoder): Unit =
    e.string(field.name)
    field.declaredShape match
      case None        => e.tag(0)
      case Some(shape) => e.tag(1); writeDeclaredShape(shape, e)
    field.value match
      case ParserFieldValue.Node(id)                => e.tag(1); e.long(id)
      case ParserFieldValue.Positioned(id)          => e.tag(2); e.long(id)
      case ParserFieldValue.Optional(value)         =>
        e.tag(3); value.fold(e.tag(0))(v => { e.tag(1); writeField(ParserSyntaxField("", v), e) })
      case ParserFieldValue.Repeated(values)        =>
        e.tag(4); e.sequence(values)(v => writeField(ParserSyntaxField("", v), e))
      case ParserFieldValue.Product(prefix, fields) => e.tag(5); e.string(prefix); e.sequence(fields)(writeField(_, e))
      case ParserFieldValue.Name(value)             => e.tag(6); e.string(value)
      case ParserFieldValue.GeneratedName(a, b, c)  => e.tag(7); e.string(a); e.string(b); e.int(c)
      case ParserFieldValue.Scalar(value)           => e.tag(8); writeScalar(value, e)
      case ParserFieldValue.Unsupported(value)      => e.tag(9); e.string(value)

  private def writeDeclaredShape(shape: ParserDeclaredShape, e: CanonicalByteEncoder): Unit = shape match
    case ParserDeclaredShape.Node            => e.tag(1)
    case ParserDeclaredShape.Positioned      => e.tag(2)
    case ParserDeclaredShape.Optional(inner) => e.tag(3); writeDeclaredShape(inner, e)
    case ParserDeclaredShape.Repeated(inner) => e.tag(4); writeDeclaredShape(inner, e)
    case ParserDeclaredShape.Name            => e.tag(5)
    case ParserDeclaredShape.Scalar(kind)    => e.tag(6); e.string(kind)

  private def writeScalar(value: ParserScalar, e: CanonicalByteEncoder): Unit = value match
    case ParserScalar.Text(v)         => e.tag(1); e.string(v)
    case ParserScalar.Integer(v)      => e.tag(2); e.int(v)
    case ParserScalar.LongInteger(v)  => e.tag(3); e.long(v)
    case ParserScalar.Decimal(v)      => e.tag(4); e.double(v)
    case ParserScalar.Logical(v)      => e.tag(5); e.boolean(v)
    case ParserScalar.Character(v)    => e.tag(6); e.char(v)
    case ParserScalar.UnitValue       => e.tag(7)
    case ParserScalar.FloatDecimal(v) => e.tag(8); e.int(java.lang.Float.floatToRawIntBits(v))

  private def writePosition(value: ParserNodePosition, e: CanonicalByteEncoder): Unit = value match
    case ParserNodePosition.Absent                           => e.tag(0)
    case ParserNodePosition.Positioned(range, point, origin) =>
      e.tag(1); writeRange(range, e); e.int(point); e.tag(origin.ordinal)

  private def writeRange(range: PcSourceRange, e: CanonicalByteEncoder): Unit =
    e.int(range.startOffset); e.int(range.endOffset)

  private def writePath(path: Vector[ParserFieldPathSegment], e: CanonicalByteEncoder): Unit =
    e.sequence(path): segment =>
      segment match
        case ParserFieldPathSegment.NamedField(name)               => e.tag(1); e.string(name)
        case ParserFieldPathSegment.OptionalNesting                => e.tag(2)
        case ParserFieldPathSegment.RepeatedIndex(index)           => e.tag(3); e.int(index)
        case ParserFieldPathSegment.NestedProductBoundary(product) => e.tag(4); e.string(product)

  private def writeCapability(value: ParserCapabilityStatus, e: CanonicalByteEncoder): Unit = value match
    case ParserCapabilityStatus.Available           => e.tag(1)
    case ParserCapabilityStatus.Unavailable(reason) => e.tag(2); e.string(reason)

private[metallurgy] final case class ParserSyntaxNode(
    id: Long,
    production: String,
    fields: Vector[ParserSyntaxField],
    position: ParserNodePosition,
    occurrences: Vector[ParserNodeOccurrence]
)

private[metallurgy] final case class ParserSyntaxField(
    name: String,
    value: ParserFieldValue,
    declaredShape: Option[ParserDeclaredShape] = None
)

private[metallurgy] enum ParserDeclaredShape:
  case Node, Positioned
  case Optional(inner: ParserDeclaredShape)
  case Repeated(inner: ParserDeclaredShape)
  case Name
  case Scalar(kind: String)

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
  case FloatDecimal(value: Float)
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
    comments: ParserCapabilityStatus,
    endMarkers: ParserCapabilityStatus,
    scannerTokens: ParserCapabilityStatus = ParserCapabilityStatus.Unavailable("exact scanner tokens are unavailable")
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
      "comments"            -> comments,
      "end markers"         -> endMarkers,
      "scanner tokens"      -> scannerTokens
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

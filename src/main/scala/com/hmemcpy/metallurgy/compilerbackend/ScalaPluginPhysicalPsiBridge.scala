package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.{ParserSyntaxSnapshot, PcSourceRange}
import com.intellij.lang.{LanguageParserDefinitions, ParserDefinition, PsiParser}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiComment, PsiElement, PsiErrorElement, PsiFile, PsiFileFactory, PsiManager, PsiWhiteSpace}
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScArgumentExprList,
  ScAssignment,
  ScExpression,
  ScGenericCall,
  ScMethodCall,
  ScReferenceExpression
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunctionDefinition,
  ScPatternDefinition,
  ScVariableDefinition
}

import java.nio.charset.StandardCharsets
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.boundary
import scala.util.boundary.break
import scala.util.control.NonFatal

private[metallurgy] final case class PhysicalPsiSourceIdentity(
    uri: String,
    digest: String,
    documentVersion: Long
)

private[metallurgy] enum PhysicalPsiOwnerRole:
  case ValueDefinition, FunctionDefinition

private[metallurgy] final case class DotcNamedArgumentEvidence(
    argumentRange: PcSourceRange,
    nameRange: PcSourceRange,
    equalsRange: PcSourceRange,
    valueRange: PcSourceRange
)

private[metallurgy] final case class DotcPhysicalPsiEvidence(
    source: PhysicalPsiSourceIdentity,
    ownerRole: PhysicalPsiOwnerRole,
    directRhsRange: PcSourceRange,
    namedArguments: Vector[DotcNamedArgumentEvidence]
)

private[metallurgy] final case class ScalaPluginPhysicalPsiRequest(
    source: PhysicalPsiSourceIdentity,
    sourceText: String,
    dotc: DotcPhysicalPsiEvidence,
    expectedCapabilitySignature: Option[String] = None
)

private[metallurgy] enum PhysicalPsiNodeRole:
  case DirectRhs
  case MethodCall
  case InvokedExpression
  case ArgumentList
  case NamedArgument
  case NamedArgumentName
  case NamedArgumentValue
  case Reference
  case Expression
  case Syntax

private[metallurgy] enum PhysicalPsiLeafRole:
  case Identifier, Equals, LeftParenthesis, RightParenthesis, Comma, Whitespace, Comment, Other

private[metallurgy] final case class PhysicalPsiNodeFact(
    id: Int,
    roles: Vector[PhysicalPsiNodeRole],
    range: PcSourceRange,
    parentId: Option[Int],
    childIds: Vector[Int]
)

private[metallurgy] final case class PhysicalPsiLeafFact(
    nodeId: Int,
    role: PhysicalPsiLeafRole,
    elementTypeEvidence: String,
    range: PcSourceRange,
    sourceSlice: String
)

private[metallurgy] final case class PhysicalPsiNamedArgumentFact(
    argumentRange: PcSourceRange,
    nameRange: PcSourceRange,
    equalsRange: PcSourceRange,
    valueRange: PcSourceRange
)

private[metallurgy] final case class PhysicalPsiParserError(range: PcSourceRange, description: String)

private[metallurgy] final case class ScalaPluginPhysicalPsiCapability(
    signature: String,
    facts: Vector[String],
    implementationEvidence: Vector[String]
)

private[metallurgy] final case class ScalaPluginPhysicalPsiWitness(
    source: PhysicalPsiSourceIdentity,
    ownerRole: PhysicalPsiOwnerRole,
    rhsRange: PcSourceRange,
    capability: ScalaPluginPhysicalPsiCapability,
    nodes: Vector[PhysicalPsiNodeFact],
    leaves: Vector[PhysicalPsiLeafFact],
    namedArguments: Vector[PhysicalPsiNamedArgumentFact],
    parserErrors: Vector[PhysicalPsiParserError],
    genericCallCount: Int,
    reconstructedUtf8: Vector[Byte],
    reconstructionHash: String
)

private[metallurgy] enum ScalaPluginPhysicalPsiResult:
  case Equal(witness: ScalaPluginPhysicalPsiWitness)
  case Conflict(differences: Vector[String])
  case Unavailable(reasons: Vector[String])

private[metallurgy] final case class PhysicalPsiFactBounds(
    workUnits: Int,
    exportedNodes: Int,
    edges: Int,
    leaves: Int,
    namedArguments: Int,
    parserErrors: Int,
    candidateRoots: Int,
    genericCalls: Int,
    depth: Int,
    totalFacts: Int
)

private[metallurgy] object PhysicalPsiFactBounds:
  private val AbsoluteVisitedPsiNodes = 65536
  private val AbsoluteNodes           = 32768
  private val AbsoluteChildClaims     = 32768
  private val AbsoluteLeaves          = 16384
  private val AbsoluteNamedArguments  = 4096
  private val AbsoluteParserErrors    = 4096
  private val AbsoluteCandidateRoots  = 1024
  private val AbsoluteGenericCalls    = 4096
  private val AbsoluteDepth           = 2048
  private val AbsoluteTotalFacts      = 131072

  def forSourceUtf16Length(length: Int): PhysicalPsiFactBounds =
    val units      = Math.max(1, length).toLong
    val visited    = scaled(units, 16, 64, AbsoluteVisitedPsiNodes)
    val nodes      = scaled(units, 8, 32, AbsoluteNodes)
    val edges      = scaled(units, 16, 64, AbsoluteChildClaims)
    val leaves     = scaled(units, 4, 16, AbsoluteLeaves)
    val named      = scaled(units, 1, 4, AbsoluteNamedArguments)
    val errors     = scaled(units, 1, 4, AbsoluteParserErrors)
    val candidates = scaled(units, 1, 4, AbsoluteCandidateRoots)
    val generic    = scaled(units, 1, 4, AbsoluteGenericCalls)
    PhysicalPsiFactBounds(
      visited,
      nodes,
      edges,
      leaves,
      named,
      errors,
      candidates,
      generic,
      scaled(units, 2, 16, AbsoluteDepth),
      Math
        .min(
          AbsoluteTotalFacts.toLong,
          visited.toLong + nodes + edges + leaves + named + errors + candidates + generic
        )
        .toInt
    )

  private def scaled(units: Long, multiplier: Int, minimum: Int, maximum: Int): Int =
    Math.max(minimum.toLong, units * multiplier).min(maximum).toInt

private[metallurgy] final case class PhysicalPsiTraversalUsage(
    workUnits: Int,
    exportedNodes: Int,
    edges: Int,
    leaves: Int,
    namedArguments: Int,
    parserErrors: Int,
    candidateRoots: Int,
    genericCalls: Int,
    maximumDepth: Int,
    totalFacts: Int
)

private[metallurgy] final case class PhysicalPsiExtractionWitness(
    candidates: Vector[ScalaPluginPhysicalPsiWitness],
    traversal: PhysicalPsiTraversalUsage
)

private[metallurgy] final case class PhysicalPsiLimitContext(path: Vector[Int], range: PcSourceRange)

private[metallurgy] enum PhysicalPsiExtractionOutcome:
  case Complete(witness: PhysicalPsiExtractionWitness)
  case LimitExceeded(
      observedAtLeast: Long,
      limit: Int,
      category: String,
      context: PhysicalPsiLimitContext
  )

private[compilerbackend] enum PhysicalPsiFactCategory(val description: String):
  case WorkUnit      extends PhysicalPsiFactCategory("work unit")
  case ExportedNode  extends PhysicalPsiFactCategory("node fact")
  case Edge          extends PhysicalPsiFactCategory("child relation")
  case Leaf          extends PhysicalPsiFactCategory("leaf fact")
  case NamedArgument extends PhysicalPsiFactCategory("named argument fact")
  case ParserError   extends PhysicalPsiFactCategory("parser error fact")
  case CandidateRoot extends PhysicalPsiFactCategory("candidate root")
  case GenericCall   extends PhysicalPsiFactCategory("generic call fact")
  case Depth         extends PhysicalPsiFactCategory("depth")
  case TotalFacts    extends PhysicalPsiFactCategory("total fact")

private[compilerbackend] final class PhysicalPsiFactBudget(bounds: PhysicalPsiFactBounds):
  private var workUnits      = 0
  private var exportedNodes  = 0
  private var edges          = 0
  private var leaves         = 0
  private var namedArguments = 0
  private var parserErrors   = 0
  private var candidateRoots = 0
  private var genericCalls   = 0
  private var maximumDepth   = 0
  private var totalFacts     = 0

  def charge(
      category: PhysicalPsiFactCategory,
      context: PhysicalPsiLimitContext
  ): Option[PhysicalPsiExtractionOutcome.LimitExceeded] =
    val (observed, limit) = category match
      case PhysicalPsiFactCategory.WorkUnit                                   =>
        workUnits += 1
        workUnits -> bounds.workUnits
      case PhysicalPsiFactCategory.ExportedNode                               =>
        exportedNodes += 1
        exportedNodes -> bounds.exportedNodes
      case PhysicalPsiFactCategory.Edge                                       =>
        edges += 1
        edges -> bounds.edges
      case PhysicalPsiFactCategory.Leaf                                       =>
        leaves += 1
        leaves -> bounds.leaves
      case PhysicalPsiFactCategory.NamedArgument                              =>
        namedArguments += 1
        namedArguments -> bounds.namedArguments
      case PhysicalPsiFactCategory.ParserError                                =>
        parserErrors += 1
        parserErrors -> bounds.parserErrors
      case PhysicalPsiFactCategory.CandidateRoot                              =>
        candidateRoots += 1
        candidateRoots -> bounds.candidateRoots
      case PhysicalPsiFactCategory.GenericCall                                =>
        genericCalls += 1
        genericCalls -> bounds.genericCalls
      case PhysicalPsiFactCategory.Depth | PhysicalPsiFactCategory.TotalFacts =>
        throw new IllegalArgumentException(s"${category.description} is not a retained fact")
    if observed > limit then Some(exceeded(observed, limit, category, context))
    else
      totalFacts += 1
      Option.when(totalFacts > bounds.totalFacts)(
        exceeded(totalFacts, bounds.totalFacts, PhysicalPsiFactCategory.TotalFacts, context)
      )

  def observeDepth(
      depth: Int,
      context: PhysicalPsiLimitContext
  ): Option[PhysicalPsiExtractionOutcome.LimitExceeded] =
    if depth > bounds.depth then Some(exceeded(depth, bounds.depth, PhysicalPsiFactCategory.Depth, context))
    else
      maximumDepth = Math.max(maximumDepth, depth)
      None

  def usage: PhysicalPsiTraversalUsage =
    PhysicalPsiTraversalUsage(
      workUnits,
      exportedNodes,
      edges,
      leaves,
      namedArguments,
      parserErrors,
      candidateRoots,
      genericCalls,
      maximumDepth,
      totalFacts
    )

  private def exceeded(
      observed: Int,
      limit: Int,
      category: PhysicalPsiFactCategory,
      context: PhysicalPsiLimitContext
  ): PhysicalPsiExtractionOutcome.LimitExceeded =
    PhysicalPsiExtractionOutcome.LimitExceeded(observed.toLong, limit, category.description, context)

private[compilerbackend] enum PhysicalPsiComparisonPhase:
  case StructuralEquality, Reconstruction, Hash

/** Parses ordinary installed Scala 3 PSI only long enough to export neutral structural evidence. */
private[metallurgy] object ScalaPluginPhysicalPsiBridge:
  private val RequiredFacts                                             = Vector(
    "ordinary-scala3-language",
    "registered-scala3-parser-definition",
    "single-synthetic-file-root",
    "nonphysical-unregistered-no-events",
    "direct-value-or-function-rhs",
    "method-call-and-argument-list",
    "assignment-left-equals-right",
    "additive-role-order=intrinsic,direct,relational",
    "ordered-parent-child-tree",
    "ordered-lossless-leaves",
    "interval-parser-errors"
  )
  private val RoleOrder                                                 = Vector(
    PhysicalPsiNodeRole.MethodCall,
    PhysicalPsiNodeRole.ArgumentList,
    PhysicalPsiNodeRole.NamedArgument,
    PhysicalPsiNodeRole.Reference,
    PhysicalPsiNodeRole.Expression,
    PhysicalPsiNodeRole.Syntax,
    PhysicalPsiNodeRole.DirectRhs,
    PhysicalPsiNodeRole.InvokedExpression,
    PhysicalPsiNodeRole.NamedArgumentName,
    PhysicalPsiNodeRole.NamedArgumentValue
  )
  private val IntrinsicRoles                                            = RoleOrder.take(6).toSet
  private val RelationalRoles                                           = Set(
    PhysicalPsiNodeRole.InvokedExpression,
    PhysicalPsiNodeRole.NamedArgumentName,
    PhysicalPsiNodeRole.NamedArgumentValue
  )
  private final case class RoleSchema(
      intrinsic: Set[PhysicalPsiNodeRole],
      additions: Set[PhysicalPsiNodeRole]
  )
  private val RoleSchemas                                               = Vector(
    RoleSchema(
      Set(PhysicalPsiNodeRole.MethodCall, PhysicalPsiNodeRole.Expression),
      Set(
        PhysicalPsiNodeRole.DirectRhs,
        PhysicalPsiNodeRole.InvokedExpression,
        PhysicalPsiNodeRole.NamedArgumentValue
      )
    ),
    RoleSchema(Set(PhysicalPsiNodeRole.ArgumentList), Set.empty),
    RoleSchema(
      Set(PhysicalPsiNodeRole.NamedArgument, PhysicalPsiNodeRole.Expression),
      Set(PhysicalPsiNodeRole.NamedArgumentValue)
    ),
    RoleSchema(
      Set(PhysicalPsiNodeRole.Reference, PhysicalPsiNodeRole.Expression),
      Set(
        PhysicalPsiNodeRole.DirectRhs,
        PhysicalPsiNodeRole.InvokedExpression,
        PhysicalPsiNodeRole.NamedArgumentName,
        PhysicalPsiNodeRole.NamedArgumentValue
      )
    ),
    RoleSchema(
      Set(PhysicalPsiNodeRole.Expression),
      Set(
        PhysicalPsiNodeRole.DirectRhs,
        PhysicalPsiNodeRole.InvokedExpression,
        PhysicalPsiNodeRole.NamedArgumentValue
      )
    ),
    RoleSchema(Set(PhysicalPsiNodeRole.Syntax), Set.empty)
  )
  private val IgnoreComparisonPhase: PhysicalPsiComparisonPhase => Unit = _ => ()

  def inspect(project: Project, request: ScalaPluginPhysicalPsiRequest): ScalaPluginPhysicalPsiResult =
    inspectWith(project, request, None, IgnoreComparisonPhase, _ => ())

  private[compilerbackend] def inspectForTest(
      project: Project,
      request: ScalaPluginPhysicalPsiRequest,
      bounds: PhysicalPsiFactBounds,
      observe: PhysicalPsiComparisonPhase => Unit,
      observeTraversal: PhysicalPsiTraversalUsage => Unit
  ): ScalaPluginPhysicalPsiResult =
    inspectWith(project, request, Some(bounds), observe, observeTraversal)

  private def inspectWith(
      project: Project,
      request: ScalaPluginPhysicalPsiRequest,
      boundsOverride: Option[PhysicalPsiFactBounds],
      observe: PhysicalPsiComparisonPhase => Unit,
      observeTraversal: PhysicalPsiTraversalUsage => Unit
  ): ScalaPluginPhysicalPsiResult =
    validateSource(request) match
      case reasons if reasons.nonEmpty => ScalaPluginPhysicalPsiResult.Unavailable(reasons)
      case _                           =>
        try
          val computation = new Computable[ScalaPluginPhysicalPsiResult]:
            override def compute(): ScalaPluginPhysicalPsiResult =
              inspectInReadAction(project, request, boundsOverride, observe, observeTraversal)
          val application = ApplicationManager.getApplication
          if application.isReadAccessAllowed then computation.compute()
          else application.runReadAction(computation)
        catch
          case control: ControlFlowException => throw control
          case NonFatal(error)               =>
            ScalaPluginPhysicalPsiResult.Unavailable(
              Vector(s"ordinary Scala 3 physical PSI capability failed: ${message(error)}")
            )
          case error: LinkageError           =>
            ScalaPluginPhysicalPsiResult.Unavailable(
              Vector(s"ordinary Scala 3 physical PSI API is unavailable: ${message(error)}")
            )

  private[compilerbackend] def compare(
      request: ScalaPluginPhysicalPsiRequest,
      outcome: PhysicalPsiExtractionOutcome
  ): ScalaPluginPhysicalPsiResult =
    compare(request, outcome, IgnoreComparisonPhase)

  private[compilerbackend] def compareForTest(
      request: ScalaPluginPhysicalPsiRequest,
      outcome: PhysicalPsiExtractionOutcome,
      observe: PhysicalPsiComparisonPhase => Unit
  ): ScalaPluginPhysicalPsiResult =
    compare(request, outcome, observe)

  private def compare(
      request: ScalaPluginPhysicalPsiRequest,
      outcome: PhysicalPsiExtractionOutcome,
      observe: PhysicalPsiComparisonPhase => Unit
  ): ScalaPluginPhysicalPsiResult =
    outcome match
      case PhysicalPsiExtractionOutcome.LimitExceeded(observed, limit, category, context) =>
        ScalaPluginPhysicalPsiResult.Conflict(
          Vector(
            s"$category count $observed exceeds source-derived limit $limit at ${show(context.range)} path ${showPath(context.path)}"
          )
        )
      case PhysicalPsiExtractionOutcome.Complete(extraction)                              =>
        extractionLimitDifference(extraction, request.sourceText.length) match
          case Some(difference) => ScalaPluginPhysicalPsiResult.Conflict(Vector(difference))
          case None             =>
            extraction.candidates match
              case Vector(witness) => compareOne(request, witness, observe)
              case Vector()        =>
                ScalaPluginPhysicalPsiResult.Conflict(
                  Vector(s"missing direct ${request.dotc.ownerRole} RHS at ${show(request.dotc.directRhsRange)}")
                )
              case many            =>
                ScalaPluginPhysicalPsiResult.Conflict(
                  Vector(
                    s"extra direct RHS candidates: expected one at ${show(request.dotc.directRhsRange)}, found ${many.size}",
                    s"candidate ranges: ${many.map(value => show(value.rhsRange)).mkString(", ")}"
                  )
                )

  private def inspectInReadAction(
      project: Project,
      request: ScalaPluginPhysicalPsiRequest,
      boundsOverride: Option[PhysicalPsiFactBounds],
      observe: PhysicalPsiComparisonPhase => Unit,
      observeTraversal: PhysicalPsiTraversalUsage => Unit
  ): ScalaPluginPhysicalPsiResult =
    val parserDefinition = LanguageParserDefinitions.INSTANCE.forLanguage(Scala3Language.INSTANCE)
    if parserDefinition == null then
      ScalaPluginPhysicalPsiResult.Unavailable(Vector("registered parser definition for ordinary Scala 3 is missing"))
    else if !parserDefinition.isInstanceOf[Scala3ParserDefinition] then
      ScalaPluginPhysicalPsiResult.Unavailable(
        Vector(
          s"registered ordinary Scala 3 parser definition has an unsupported surface: ${parserDefinition.getClass.getName}"
        )
      )
    else
      val parser = parserDefinition.createParser(project)
      if !parser.isInstanceOf[PsiParser] then
        ScalaPluginPhysicalPsiResult.Unavailable(Vector("registered ordinary Scala 3 parser does not expose PsiParser"))
      else
        val modificationTracker = PsiModificationTracker.getInstance(project)
        val modificationBefore  = modificationTracker.getModificationCount
        val file                = PsiFileFactory
          .getInstance(project)
          .createFileFromText(
            "PhysicalPsiWitness.scala",
            Scala3Language.INSTANCE,
            request.sourceText,
            false,
            false
          )
        val result              =
          inspectSyntheticFile(
            project,
            request,
            parserDefinition,
            parser,
            file,
            boundsOverride,
            observe,
            observeTraversal
          )
        if modificationTracker.getModificationCount != modificationBefore then
          ScalaPluginPhysicalPsiResult.Unavailable(
            Vector("ordinary Scala 3 witness parse changed the project PSI modification count")
          )
        else result

  private def inspectSyntheticFile(
      project: Project,
      request: ScalaPluginPhysicalPsiRequest,
      parserDefinition: ParserDefinition,
      parser: PsiParser,
      file: PsiFile,
      boundsOverride: Option[PhysicalPsiFactBounds],
      observe: PhysicalPsiComparisonPhase => Unit,
      observeTraversal: PhysicalPsiTraversalUsage => Unit
  ): ScalaPluginPhysicalPsiResult =
    if file.getLanguage != Scala3Language.INSTANCE then
      ScalaPluginPhysicalPsiResult.Unavailable(
        Vector(s"synthetic witness selected the wrong language: ${file.getLanguage.getID}")
      )
    else if file.getViewProvider.isPhysical then
      ScalaPluginPhysicalPsiResult.Unavailable(
        Vector("synthetic ordinary Scala 3 witness unexpectedly became physical")
      )
    else if file.getVirtualFile != null &&
      PsiManager.getInstance(project).findCachedViewProvider(file.getVirtualFile) != null
    then
      ScalaPluginPhysicalPsiResult.Unavailable(
        Vector("synthetic ordinary Scala 3 witness was registered in the project PSI file manager")
      )
    else
      val roots = file.getViewProvider.getAllFiles.asScala.toVector
      if roots.size != 1 || !(roots.head eq file) then
        ScalaPluginPhysicalPsiResult.Unavailable(
          Vector(s"synthetic ordinary Scala 3 file has ${roots.size} PSI roots instead of one")
        )
      else
        val capability = capabilityFor(parserDefinition, parser, file)
        request.expectedCapabilitySignature match
          case Some(expected) if expected != capability.signature =>
            ScalaPluginPhysicalPsiResult.Unavailable(
              Vector(s"host capability signature changed: expected $expected, found ${capability.signature}")
            )
          case _                                                  =>
            val bounds  = boundsOverride.getOrElse(PhysicalPsiFactBounds.forSourceUtf16Length(request.sourceText.length))
            val outcome = extract(file, request, capability, bounds)
            outcome match
              case PhysicalPsiExtractionOutcome.Complete(witness) => observeTraversal(witness.traversal)
              case _                                              => ()
            compare(request, outcome, observe)

  private def validateSource(request: ScalaPluginPhysicalPsiRequest): Vector[String] =
    val actualDigest = ParserSyntaxSnapshot.digest(request.sourceText)
    Vector
      .newBuilder[String]
      .addAll(Option.when(request.source.uri.isEmpty)("source identity URI is missing"))
      .addAll(Option.when(request.source.documentVersion < 0)("source document version is invalid"))
      .addAll(
        Option.when(request.source.digest != actualDigest)(
          s"source is stale: supplied digest ${request.source.digest}, exact text digest $actualDigest"
        )
      )
      .addAll(
        Option.when(request.dotc.source != request.source)(
          s"dotc source identity is stale: expected ${request.source}, found ${request.dotc.source}"
        )
      )
      .result()

  private def capabilityFor(
      parserDefinition: ParserDefinition,
      parser: PsiParser,
      file: PsiElement
  ): ScalaPluginPhysicalPsiCapability =
    val implementationEvidence = Vector(
      s"parser-definition=${parserDefinition.getClass.getName}",
      s"parser=${parser.getClass.getName}",
      s"file=${file.getClass.getName}",
      s"file-node-language=${parserDefinition.getFileNodeType.getLanguage.getID}"
    )
    ScalaPluginPhysicalPsiCapability(
      ParserSyntaxSnapshot.digest((RequiredFacts ++ implementationEvidence).mkString("\n")),
      RequiredFacts,
      implementationEvidence
    )

  private def compareOne(
      request: ScalaPluginPhysicalPsiRequest,
      witness: ScalaPluginPhysicalPsiWitness,
      observe: PhysicalPsiComparisonPhase => Unit
  ): ScalaPluginPhysicalPsiResult =
    val rangeDifferences = sourceRangeDifferences(witness, request)
    if rangeDifferences.nonEmpty then ScalaPluginPhysicalPsiResult.Conflict(rangeDifferences)
    else
      observe(PhysicalPsiComparisonPhase.StructuralEquality)
      val structuralDifferences = preflightDifferences(witness, request)
      if structuralDifferences.nonEmpty then ScalaPluginPhysicalPsiResult.Conflict(structuralDifferences)
      else compareValidated(request, witness, observe)

  private def compareValidated(
      request: ScalaPluginPhysicalPsiRequest,
      witness: ScalaPluginPhysicalPsiWitness,
      observe: PhysicalPsiComparisonPhase => Unit
  ): ScalaPluginPhysicalPsiResult =
    observe(PhysicalPsiComparisonPhase.Reconstruction)
    val expectedText  = slice(request.sourceText, request.dotc.directRhsRange)
    val reconstructed = witness.leaves.flatMap(_.sourceSlice.getBytes(StandardCharsets.UTF_8))
    val actualText    = new String(reconstructed.toArray, StandardCharsets.UTF_8)
    if actualText != expectedText then
      ScalaPluginPhysicalPsiResult.Conflict(
        Vector(s"changed reconstruction text: expected ${quoted(expectedText)}, found ${quoted(actualText)}")
      )
    else
      observe(PhysicalPsiComparisonPhase.Hash)
      ScalaPluginPhysicalPsiResult.Equal(
        witness.copy(reconstructedUtf8 = reconstructed, reconstructionHash = ParserSyntaxSnapshot.digest(actualText))
      )

  private final class MutableNodeFact(
      val id: Int,
      val roles: Vector[PhysicalPsiNodeRole],
      val range: PcSourceRange,
      val parentId: Option[Int]
  ):
    val childIds: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty

  private final class MutableNamedArgument(
      val argumentRange: PcSourceRange,
      val nameRange: PcSourceRange,
      val valueRange: PcSourceRange
  ):
    var equalsRange: Option[PcSourceRange] = None
    var duplicateEquals                    = false

  private final class ExtractionAccumulator(val rhs: ScExpression, val ownerRole: PhysicalPsiOwnerRole):
    var nextId                                                        = 1
    val nodes: mutable.ArrayBuffer[MutableNodeFact]                   = mutable.ArrayBuffer.empty
    val leaves: mutable.ArrayBuffer[PhysicalPsiLeafFact]              = mutable.ArrayBuffer.empty
    val namedByNodeId: mutable.Map[Int, MutableNamedArgument]         = mutable.Map.empty
    val namedInSourceOrder: mutable.ArrayBuffer[MutableNamedArgument] = mutable.ArrayBuffer.empty
    val errors: mutable.ArrayBuffer[PhysicalPsiParserError]           = mutable.ArrayBuffer.empty
    var generic                                                       = 0

  private final case class ActiveParent(accumulator: ExtractionAccumulator, id: Int)
  private sealed trait TraversalFrame
  private final case class VisitElement(
      element: PsiElement,
      activeParent: Option[ActiveParent],
      path: Vector[Int],
      depth: Int
  ) extends TraversalFrame
  private final case class ResumeChildren(
      parent: PsiElement,
      previousChild: Option[PsiElement],
      activeParent: Option[ActiveParent],
      parentPath: Vector[Int],
      nextIndex: Int,
      childDepth: Int
  ) extends TraversalFrame

  private def extract(
      file: PsiElement,
      request: ScalaPluginPhysicalPsiRequest,
      capability: ScalaPluginPhysicalPsiCapability,
      bounds: PhysicalPsiFactBounds
  ): PhysicalPsiExtractionOutcome = boundary:
    val pending      = mutable.ArrayDeque.empty[TraversalFrame]
    val accumulators = mutable.ArrayBuffer.empty[ExtractionAccumulator]
    val budget       = new PhysicalPsiFactBudget(bounds)

    def stop(exceeded: Option[PhysicalPsiExtractionOutcome.LimitExceeded]): Unit =
      exceeded match
        case Some(exceeded) => break(exceeded)
        case None           => ()

    val rootContext = PhysicalPsiLimitContext(Vector.empty, range(file))
    stop(budget.charge(PhysicalPsiFactCategory.WorkUnit, rootContext))
    stop(budget.observeDepth(0, rootContext))
    pending.append(VisitElement(file, None, Vector.empty, 0))

    while pending.nonEmpty do
      pending.removeLast() match
        case ResumeChildren(parent, previousChild, activeParent, parentPath, nextIndex, childDepth) =>
          val child = previousChild.fold(parent.getFirstChild)(_.getNextSibling)
          if child != null then
            val path    = parentPath :+ nextIndex
            val context = PhysicalPsiLimitContext(path, range(child))
            stop(budget.charge(PhysicalPsiFactCategory.Edge, context))
            stop(budget.charge(PhysicalPsiFactCategory.WorkUnit, context))
            stop(budget.observeDepth(childDepth, context))
            stop(
              budget.charge(
                PhysicalPsiFactCategory.WorkUnit,
                PhysicalPsiLimitContext(parentPath, range(parent))
              )
            )
            stop(
              budget.observeDepth(
                Math.max(0, childDepth - 1),
                PhysicalPsiLimitContext(parentPath, range(parent))
              )
            )
            pending.append(
              ResumeChildren(parent, Some(child), activeParent, parentPath, nextIndex + 1, childDepth)
            )
            pending.append(VisitElement(child, activeParent, path, childDepth))
          else if previousChild.isEmpty then
            activeParent.foreach: state =>
              val context      = PhysicalPsiLimitContext(parentPath, range(parent))
              stop(budget.charge(PhysicalPsiFactCategory.Leaf, context))
              val elementRange = range(parent)
              state.accumulator.leaves += PhysicalPsiLeafFact(
                state.id,
                leafRole(parent),
                parent.getNode.getElementType.toString,
                elementRange,
                slice(request.sourceText, elementRange)
              )
              if parent.getNode.getElementType == ScalaTokenTypes.tASSIGN then
                state.accumulator
                  .nodes(state.id)
                  .parentId
                  .flatMap(state.accumulator.namedByNodeId.get)
                  .foreach: named =>
                    named.equalsRange match
                      case None    => named.equalsRange = Some(elementRange)
                      case Some(_) => named.duplicateEquals = true

        case VisitElement(element, inheritedParent, path, depth) =>
          val context       = PhysicalPsiLimitContext(path, range(element))
          val activeElement = ownerRoute(element, request.dotc) match
            case Some((role, rhs)) =>
              stop(budget.charge(PhysicalPsiFactCategory.CandidateRoot, context))
              val accumulator = new ExtractionAccumulator(rhs, role)
              accumulators += accumulator
              Some(accumulator -> Option.empty[Int])
            case None              => inheritedParent.map(parent => parent.accumulator -> Some(parent.id))

          val activeParent = activeElement.map: (accumulator, parentId) =>
            val id = parentId.fold(0)(_ => accumulator.nextId)
            stop(budget.charge(PhysicalPsiFactCategory.ExportedNode, context))
            parentId.foreach: parent =>
              accumulator.nextId += 1
              accumulator.nodes(parent).childIds += id
            accumulator.nodes +=
              new MutableNodeFact(id, nodeRoles(element, accumulator.rhs), range(element), parentId)
            element match
              case assignment: ScAssignment =>
                (assignment.getParent, assignment.leftExpression, assignment.rightExpression) match
                  case (_: ScArgumentExprList, name: ScReferenceExpression, Some(value)) =>
                    stop(budget.charge(PhysicalPsiFactCategory.NamedArgument, context))
                    val named = new MutableNamedArgument(range(assignment), range(name), range(value))
                    accumulator.namedByNodeId.update(id, named)
                    accumulator.namedInSourceOrder += named
                  case _                                                                 => ()
              case error: PsiErrorElement   =>
                stop(budget.charge(PhysicalPsiFactCategory.ParserError, context))
                accumulator.errors += PhysicalPsiParserError(range(error), error.getErrorDescription)
              case _: ScGenericCall         =>
                stop(budget.charge(PhysicalPsiFactCategory.GenericCall, context))
                accumulator.generic += 1
              case _                        => ()
            ActiveParent(accumulator, id)

          stop(budget.charge(PhysicalPsiFactCategory.WorkUnit, context))
          stop(budget.observeDepth(depth, context))
          pending.append(ResumeChildren(element, None, activeParent, path, 0, depth + 1))

    val witnessBuilder = Vector.newBuilder[ScalaPluginPhysicalPsiWitness]
    accumulators.foreach: accumulator =>
      val nodeBuilder  = Vector.newBuilder[PhysicalPsiNodeFact]
      accumulator.nodes.foreach: node =>
        nodeBuilder += PhysicalPsiNodeFact(node.id, node.roles, node.range, node.parentId, node.childIds.toVector)
      val namedBuilder = Vector.newBuilder[PhysicalPsiNamedArgumentFact]
      accumulator.namedInSourceOrder.foreach: value =>
        if !value.duplicateEquals then
          value.equalsRange.foreach: equalsRange =>
            namedBuilder += PhysicalPsiNamedArgumentFact(
              value.argumentRange,
              value.nameRange,
              equalsRange,
              value.valueRange
            )
      witnessBuilder += ScalaPluginPhysicalPsiWitness(
        request.source,
        accumulator.ownerRole,
        range(accumulator.rhs),
        capability,
        nodeBuilder.result(),
        accumulator.leaves.toVector,
        namedBuilder.result(),
        accumulator.errors.toVector,
        accumulator.generic,
        Vector.empty,
        ""
      )
    PhysicalPsiExtractionOutcome.Complete(
      PhysicalPsiExtractionWitness(
        witnessBuilder.result(),
        budget.usage
      )
    )

  private def ownerRoute(
      element: PsiElement,
      evidence: DotcPhysicalPsiEvidence
  ): Option[(PhysicalPsiOwnerRole, ScExpression)] =
    val route = element match
      case rhs: ScExpression if range(rhs) == evidence.directRhsRange =>
        rhs.getParent match
          case definition: ScPatternDefinition if definition.expr.exists(_ eq rhs)  =>
            Some(PhysicalPsiOwnerRole.ValueDefinition -> rhs)
          case definition: ScVariableDefinition if definition.expr.exists(_ eq rhs) =>
            Some(PhysicalPsiOwnerRole.ValueDefinition -> rhs)
          case definition: ScFunctionDefinition if definition.body.exists(_ eq rhs) =>
            Some(PhysicalPsiOwnerRole.FunctionDefinition -> rhs)
          case _                                                                    => None
      case _                                                          => None
    route.filter(_._1 == evidence.ownerRole)

  private def nodeRoles(element: PsiElement, rhs: ScExpression): Vector[PhysicalPsiNodeRole] =
    val intrinsic  = element match
      case _: ScMethodCall          => Vector(PhysicalPsiNodeRole.MethodCall, PhysicalPsiNodeRole.Expression)
      case _: ScArgumentExprList    => Vector(PhysicalPsiNodeRole.ArgumentList)
      case _: ScAssignment          => Vector(PhysicalPsiNodeRole.NamedArgument, PhysicalPsiNodeRole.Expression)
      case _: ScReferenceExpression => Vector(PhysicalPsiNodeRole.Reference, PhysicalPsiNodeRole.Expression)
      case _: ScExpression          => Vector(PhysicalPsiNodeRole.Expression)
      case _                        => Vector(PhysicalPsiNodeRole.Syntax)
    val direct     = Option.when(element eq rhs)(PhysicalPsiNodeRole.DirectRhs).toVector
    val relational =
      element.getParent match
        case call: ScMethodCall if call.getInvokedExpr eq element                        => Vector(PhysicalPsiNodeRole.InvokedExpression)
        case assignment: ScAssignment if assignment.leftExpression eq element            =>
          Vector(PhysicalPsiNodeRole.NamedArgumentName)
        case assignment: ScAssignment if assignment.rightExpression.exists(_ eq element) =>
          Vector(PhysicalPsiNodeRole.NamedArgumentValue)
        case _                                                                           => Vector.empty
    intrinsic ++ direct ++ relational

  private def leafRole(element: PsiElement): PhysicalPsiLeafRole =
    element match
      case _: PsiWhiteSpace => PhysicalPsiLeafRole.Whitespace
      case _: PsiComment    => PhysicalPsiLeafRole.Comment
      case _                =>
        element.getText match
          case "="                                              => PhysicalPsiLeafRole.Equals
          case "("                                              => PhysicalPsiLeafRole.LeftParenthesis
          case ")"                                              => PhysicalPsiLeafRole.RightParenthesis
          case ","                                              => PhysicalPsiLeafRole.Comma
          case text if text.matches("[A-Za-z_$][A-Za-z0-9_$]*") => PhysicalPsiLeafRole.Identifier
          case _                                                => PhysicalPsiLeafRole.Other

  private def sourceRangeDifferences(
      witness: ScalaPluginPhysicalPsiWitness,
      request: ScalaPluginPhysicalPsiRequest
  ): Vector[String] =
    val sourceLength = request.sourceText.length
    val differences  = Vector.newBuilder[String]

    def check(label: String, sourceRange: PcSourceRange): Unit =
      if !validRange(sourceRange, sourceLength) then
        differences += s"invalid $label range ${show(sourceRange)} outside exact source [0,$sourceLength]"

    check("request/dotc direct RHS", request.dotc.directRhsRange)
    check("witness direct RHS", witness.rhsRange)
    request.dotc.namedArguments.zipWithIndex.foreach: (named, index) =>
      Vector(
        "argument" -> named.argumentRange,
        "name"     -> named.nameRange,
        "equals"   -> named.equalsRange,
        "value"    -> named.valueRange
      ).foreach: (part, sourceRange) =>
        check(s"request/dotc named argument $index $part", sourceRange)
    witness.namedArguments.zipWithIndex.foreach: (named, index) =>
      Vector(
        "argument" -> named.argumentRange,
        "name"     -> named.nameRange,
        "equals"   -> named.equalsRange,
        "value"    -> named.valueRange
      ).foreach: (part, sourceRange) =>
        check(s"witness named argument $index $part", sourceRange)
    witness.nodes.foreach(node => check(s"node ${node.id}", node.range))
    witness.leaves.foreach(leaf => check(s"leaf ${leaf.nodeId}", leaf.range))
    witness.parserErrors.foreach(error => check("parser error", error.range))
    differences.result()

  private def extractionLimitDifference(
      extraction: PhysicalPsiExtractionWitness,
      sourceLength: Int
  ): Option[String] =
    val bounds         = PhysicalPsiFactBounds.forSourceUtf16Length(sourceLength)
    val traversal      = extraction.traversal
    val candidates     = extraction.candidates
    val nodeFacts      = Math.max(traversal.exportedNodes.toLong, candidates.iterator.map(_.nodes.size.toLong).sum)
    val childClaims    = Math.max(
      traversal.edges.toLong,
      candidates.iterator.flatMap(_.nodes).map(_.childIds.size.toLong).sum
    )
    val leafFacts      = Math.max(traversal.leaves.toLong, candidates.iterator.map(_.leaves.size.toLong).sum)
    val namedFacts     = Math.max(
      traversal.namedArguments.toLong,
      candidates.iterator.map(_.namedArguments.size.toLong).sum
    )
    val errorFacts     = Math.max(traversal.parserErrors.toLong, candidates.iterator.map(_.parserErrors.size.toLong).sum)
    val candidateFacts = Math.max(traversal.candidateRoots.toLong, candidates.size.toLong)
    val genericFacts   = Math.max(traversal.genericCalls.toLong, candidates.iterator.map(_.genericCallCount.toLong).sum)
    val categoryLimits = Vector(
      ("work unit", traversal.workUnits.toLong, bounds.workUnits),
      ("node fact", nodeFacts, bounds.exportedNodes),
      ("child relation", childClaims, bounds.edges),
      ("leaf fact", leafFacts, bounds.leaves),
      ("named argument fact", namedFacts, bounds.namedArguments),
      ("parser error fact", errorFacts, bounds.parserErrors),
      ("candidate root", candidateFacts, bounds.candidateRoots),
      ("generic call fact", genericFacts, bounds.genericCalls),
      ("depth", traversal.maximumDepth.toLong, bounds.depth)
    )
    categoryLimits
      .collectFirst {
        case (category, observed, limit) if observed > limit =>
          s"$category count $observed exceeds source-derived limit $limit"
      }
      .orElse:
        val total         =
          traversal.workUnits.toLong + nodeFacts + childClaims + leafFacts + namedFacts + errorFacts +
            candidateFacts + genericFacts
        val observedTotal = Math.max(total, traversal.totalFacts.toLong)
        Option.when(observedTotal > bounds.totalFacts)(
          s"total fact count $observedTotal exceeds source-derived limit ${bounds.totalFacts}"
        )

  private def preflightDifferences(
      witness: ScalaPluginPhysicalPsiWitness,
      request: ScalaPluginPhysicalPsiRequest
  ): Vector[String] =
    val differences        = Vector.newBuilder[String]
    val source             = request.sourceText
    val actualDigest       = ParserSyntaxSnapshot.digest(source)
    val identities         = Vector(request.source, request.dotc.source, witness.source)
    val bounds             = PhysicalPsiFactBounds.forSourceUtf16Length(source.length)
    val limitDifferences   = Vector.newBuilder[String]
    Vector(
      ("node", witness.nodes.size.toLong, bounds.exportedNodes),
      ("leaf", witness.leaves.size.toLong, bounds.leaves),
      ("named argument", witness.namedArguments.size.toLong, bounds.namedArguments),
      ("parser error", witness.parserErrors.size.toLong, bounds.parserErrors),
      ("generic call", witness.genericCallCount.toLong, bounds.genericCalls)
    ).foreach: (label, count, limit) =>
      if count > limit then limitDifferences += s"$label fact count $count exceeds source-derived limit $limit"
    val childClaimCount    = witness.nodes.iterator.map(_.childIds.size.toLong).sum
    if childClaimCount > bounds.edges then
      limitDifferences +=
        s"child relation count $childClaimCount exceeds source-derived limit ${bounds.edges}"
    val totalFactCount     =
      witness.nodes.size.toLong + childClaimCount + witness.leaves.size + witness.namedArguments.size +
        witness.parserErrors.size + witness.genericCallCount
    if totalFactCount > bounds.totalFacts then
      limitDifferences += s"total fact count $totalFactCount exceeds source-derived limit ${bounds.totalFacts}"
    val boundedDifferences = limitDifferences.result()
    if boundedDifferences.nonEmpty then return boundedDifferences
    witness.nodes.foreach: node =>
      if !contains(witness.rhsRange, node.range) then
        differences += s"invalid node ${node.id} range ${show(node.range)} outside direct RHS ${show(witness.rhsRange)}"
    witness.leaves.foreach: leaf =>
      if !contains(witness.rhsRange, leaf.range) then
        differences +=
          s"invalid leaf ${leaf.nodeId} range ${show(leaf.range)} outside direct RHS ${show(witness.rhsRange)}"
    witness.parserErrors.foreach: error =>
      if !contains(witness.rhsRange, error.range) then
        differences += s"invalid parser error range ${show(error.range)} outside direct RHS ${show(witness.rhsRange)}"
    if identities.distinct.size != 1 then
      differences +=
        s"changed source identities: request=${request.source}, dotc=${request.dotc.source}, witness=${witness.source}"
    identities
      .zip(Vector("request", "dotc", "witness"))
      .foreach: (identity, owner) =>
        if identity.digest != actualDigest then
          differences += s"changed $owner source digest: expected $actualDigest, found ${identity.digest}"
        if identity.uri.isEmpty then differences += s"missing $owner source URI"
        if identity.documentVersion < 0 then
          differences += s"invalid $owner document version: ${identity.documentVersion}"
    if witness.ownerRole != request.dotc.ownerRole then
      differences += s"changed owner role: expected ${request.dotc.ownerRole}, found ${witness.ownerRole}"
    if witness.rhsRange != request.dotc.directRhsRange then
      differences += s"changed direct RHS range: expected ${show(request.dotc.directRhsRange)}, found ${show(witness.rhsRange)}"
    if witness.nodes.headOption.forall(!_.roles.contains(PhysicalPsiNodeRole.DirectRhs)) then
      differences += "missing direct RHS root role"
    if witness.nodes.count(_.roles.contains(PhysicalPsiNodeRole.MethodCall)) == 0 then
      differences += "missing method-call role in the direct RHS"
    if witness.nodes.count(_.roles.contains(PhysicalPsiNodeRole.ArgumentList)) == 0 then
      differences += "missing argument-list role in the direct RHS"
    if witness.genericCallCount != 0 then
      differences += s"extra TypeApply/generic-call structure: found ${witness.genericCallCount}"
    val expectedNamed      = request.dotc.namedArguments.map(value =>
      PhysicalPsiNamedArgumentFact(value.argumentRange, value.nameRange, value.equalsRange, value.valueRange)
    )
    if expectedNamed.isEmpty then differences += "missing caller-provided dotc named-argument evidence"
    if witness.namedArguments != expectedNamed then
      differences ++= orderedDifference(
        "named argument",
        expectedNamed.map(showNamed),
        witness.namedArguments.map(showNamed)
      )
    witness.parserErrors.foreach(error => differences += s"parser error at ${show(error.range)}: ${error.description}")
    witness.nodes
      .groupBy(_.id)
      .collect { case (id, records) if records.size > 1 => id }
      .toVector
      .sorted
      .foreach(id => differences += s"duplicate node record id: $id")
    val byId               = witness.nodes.map(node => node.id -> node).toMap
    differences ++= namedEvidenceDifferences("dotc", expectedNamed, witness, byId, source.length)
    differences ++= namedEvidenceDifferences("witness", witness.namedArguments, witness, byId, source.length)
    val roots              = witness.nodes.filter(_.parentId.isEmpty)
    if roots.size != 1 || roots.headOption.forall(_.id != 0) then
      differences += s"changed tree roots: expected only node 0, found ${roots.map(_.id)}"
    val childClaims        = witness.nodes.flatMap(_.childIds)
    val claimCounts        = childClaims.groupMapReduce(identity)(_ => 1)(_ + _)
    claimCounts
      .collect { case (id, claims) if claims > 1 => id }
      .toVector
      .sorted
      .foreach(id => differences += s"duplicate child relation for node $id")
    witness.nodes.foreach: node =>
      val incoming = claimCounts.getOrElse(node.id, 0)
      val expected = if node.id == 0 then 0 else 1
      if incoming != expected then
        differences += s"changed child ownership for node ${node.id}: expected $expected incoming relation, found $incoming"
    witness.nodes.foreach: node =>
      differences ++= roleDifferences(node, byId, witness)
      node.parentId.foreach: parentId =>
        if !byId.get(parentId).exists(_.childIds.contains(node.id)) then
          differences += s"changed parent/child relation: node ${node.id} names parent $parentId without matching child order"
      node.childIds.foreach: childId =>
        if !byId.get(childId).exists(_.parentId.contains(node.id)) then
          differences += s"changed parent/child relation: node ${node.id} names child $childId without matching parent"
      val children = node.childIds.flatMap(byId.get)
      if children.size == node.childIds.size && children.nonEmpty then
        if children.head.range.startOffset != node.range.startOffset then
          differences +=
            s"changed child partition under node ${node.id}: first child starts at ${children.head.range.startOffset}, parent starts at ${node.range.startOffset}"
        children
          .sliding(2)
          .foreach:
            case Vector(left, right) if left.range.endOffset != right.range.startOffset =>
              differences +=
                s"changed child partition under node ${node.id}: ${show(left.range)} is not adjacent to ${show(right.range)}"
            case _                                                                      => ()
        if children.last.range.endOffset != node.range.endOffset then
          differences +=
            s"changed child partition under node ${node.id}: last child ends at ${children.last.range.endOffset}, parent ends at ${node.range.endOffset}"
    val reachable          = scala.collection.mutable.Set.empty[Int]
    var pending            = List(0 -> 0)
    while pending.nonEmpty do
      val (id, depth) = pending.head
      pending = pending.tail
      if depth > bounds.depth then
        differences += s"tree depth $depth exceeds source-derived limit ${bounds.depth} at node $id"
      if !reachable(id) then
        reachable += id
        pending = byId.get(id).toList.flatMap(_.childIds.map(_ -> (depth + 1))) ::: pending
    witness.nodes
      .map(_.id)
      .distinct
      .filterNot(reachable)
      .sorted
      .foreach(id => differences += s"unreachable node record id: $id")
    val indegrees          = scala.collection.mutable.Map.from(byId.keysIterator.map(_ -> 0))
    witness.nodes.foreach(
      _.childIds.foreach(childId => if byId.contains(childId) then indegrees.update(childId, indegrees(childId) + 1))
    )
    val ready              = scala.collection.mutable.Queue.from(indegrees.iterator.collect { case (id, 0) => id })
    var processed          = 0
    while ready.nonEmpty do
      val id = ready.dequeue()
      processed += 1
      byId
        .get(id)
        .foreach(
          _.childIds.foreach(childId =>
            indegrees
              .get(childId)
              .foreach: degree =>
                val next = degree - 1
                indegrees.update(childId, next)
                if next == 0 then ready.enqueue(childId)
          )
        )
    if processed != byId.size then
      differences +=
        s"cycle detected among node ids: ${indegrees.collect { case (id, degree) if degree > 0 => id }.toVector.sorted}"
    val leavesById         = witness.leaves.groupBy(_.nodeId)
    witness.leaves.foreach: leaf =>
      byId.get(leaf.nodeId) match
        case None       => differences += s"leaf names missing node ${leaf.nodeId}"
        case Some(node) =>
          if node.childIds.nonEmpty then differences += s"leaf names nonterminal node ${leaf.nodeId}"
          if node.range != leaf.range then
            differences += s"changed leaf/node range for node ${leaf.nodeId}: node ${show(node.range)}, leaf ${show(leaf.range)}"
    witness.nodes
      .filter(_.childIds.isEmpty)
      .foreach: node =>
        val count = leavesById.getOrElse(node.id, Vector.empty).size
        if count != 1 then differences += s"terminal node ${node.id} has $count leaf records instead of one"
        if node.roles != Vector(PhysicalPsiNodeRole.Syntax) then
          differences += s"leaf-incompatible roles on terminal node ${node.id}: ${node.roles}"
    var offset             = witness.rhsRange.startOffset
    witness.leaves.foreach: leaf =>
      if leaf.range.startOffset != offset then
        differences += s"unowned or overlapping leaf bytes before ${show(leaf.range)}: expected next offset $offset"
      if leaf.range.endOffset <= leaf.range.startOffset then
        differences += s"empty or reversed leaf range: ${show(leaf.range)}"
      if leaf.sourceSlice != slice(source, leaf.range) then
        differences += s"changed leaf slice at ${show(leaf.range)}: expected ${quoted(slice(source, leaf.range))}, found ${quoted(leaf.sourceSlice)}"
      offset = leaf.range.endOffset
    if offset != witness.rhsRange.endOffset then
      differences += s"unowned trailing leaf bytes: reconstructed through $offset, RHS ends at ${witness.rhsRange.endOffset}"
    differences.result()

  private def namedEvidenceDifferences(
      owner: String,
      facts: Vector[PhysicalPsiNamedArgumentFact],
      witness: ScalaPluginPhysicalPsiWitness,
      byId: Map[Int, PhysicalPsiNodeFact],
      sourceLength: Int
  ): Vector[String] =
    val differences        = Vector.newBuilder[String]
    var previousEnd        = witness.rhsRange.startOffset
    val treeArgumentRanges = witness.nodes
      .filter(_.roles.contains(PhysicalPsiNodeRole.NamedArgument))
      .map(_.range)
    if treeArgumentRanges != facts.map(_.argumentRange) then
      differences +=
        s"invalid $owner named argument node ranges: facts ${facts.map(value => show(value.argumentRange))}, tree ${treeArgumentRanges.map(show)}"
    facts.zipWithIndex.foreach: (fact, index) =>
      val label  = s"$owner named argument $index"
      val ranges = Vector(
        "argument" -> fact.argumentRange,
        "name"     -> fact.nameRange,
        "equals"   -> fact.equalsRange,
        "value"    -> fact.valueRange
      )
      ranges.foreach: (part, partRange) =>
        if !validRange(partRange, sourceLength) then
          differences += s"invalid $label $part range: ${show(partRange)} is outside the exact source"
      if ranges.forall(value => validRange(value._2, sourceLength)) then
        if !contains(witness.rhsRange, fact.argumentRange) then
          differences +=
            s"invalid $label range: ${show(fact.argumentRange)} is outside direct RHS ${show(witness.rhsRange)}"
        if fact.argumentRange.startOffset != fact.nameRange.startOffset ||
          fact.argumentRange.endOffset != fact.valueRange.endOffset
        then
          differences +=
            s"invalid $label partition: argument ${show(fact.argumentRange)}, name ${show(fact.nameRange)}, value ${show(fact.valueRange)}"
        if !contains(fact.argumentRange, fact.nameRange) ||
          !contains(fact.argumentRange, fact.equalsRange) ||
          !contains(fact.argumentRange, fact.valueRange)
        then differences += s"invalid $label containment within ${show(fact.argumentRange)}"
        if fact.nameRange.endOffset > fact.equalsRange.startOffset ||
          fact.equalsRange.endOffset > fact.valueRange.startOffset
        then differences += s"invalid $label source order"
        if fact.nameRange.startOffset == fact.nameRange.endOffset then differences += s"invalid $label empty name range"
        if fact.argumentRange.startOffset < previousEnd then
          differences += s"reordered or overlapping $owner named argument facts at index $index"
        previousEnd = fact.argumentRange.endOffset

        val argumentNodes = witness.nodes.filter(node =>
          node.range == fact.argumentRange && node.roles.contains(PhysicalPsiNodeRole.NamedArgument)
        )
        if argumentNodes.size != 1 then
          differences += s"invalid $label node match: found ${argumentNodes.size} named-argument nodes"
        argumentNodes.headOption.foreach: argumentNode =>
          val inArgumentList = argumentNode.parentId
            .flatMap(byId.get)
            .exists(node =>
              node.roles.contains(PhysicalPsiNodeRole.ArgumentList) && contains(node.range, fact.argumentRange)
            )
          if !inArgumentList then differences += s"invalid $label argument-list ownership"

        val nameNodes  = witness.nodes.filter(node =>
          node.range == fact.nameRange && node.roles.contains(PhysicalPsiNodeRole.NamedArgumentName)
        )
        if nameNodes.size != 1 then differences += s"invalid $label name-node match: found ${nameNodes.size} nodes"
        val valueNodes = witness.nodes.filter(node =>
          node.range == fact.valueRange && node.roles.contains(PhysicalPsiNodeRole.NamedArgumentValue)
        )
        if valueNodes.size != 1 then differences += s"invalid $label value-node match: found ${valueNodes.size} nodes"

        if fact.nameRange.endOffset <= fact.valueRange.startOffset then
          val gapStart     = fact.nameRange.endOffset
          val gapEnd       = fact.valueRange.startOffset
          val gapLeaves    =
            witness.leaves.filter(leaf => leaf.range.startOffset >= gapStart && leaf.range.endOffset <= gapEnd)
          if gapLeaves.headOption.forall(_.range.startOffset != gapStart) ||
            gapLeaves.lastOption.forall(_.range.endOffset != gapEnd) ||
            gapLeaves
              .sliding(2)
              .exists:
                case Vector(left, right) => left.range.endOffset != right.range.startOffset
                case _                   => false
          then differences += s"invalid $label gap leaf partition"
          val equalsLeaves = gapLeaves.filter(_.role == PhysicalPsiLeafRole.Equals)
          if equalsLeaves.size != 1 then
            differences += s"invalid $label equals leaves: found ${equalsLeaves.size} instead of one"
          equalsLeaves.headOption.foreach: equalsLeaf =>
            if equalsLeaf.range != fact.equalsRange then
              differences +=
                s"invalid $label equals range: fact ${show(fact.equalsRange)}, leaf ${show(equalsLeaf.range)}"
            if equalsLeaf.sourceSlice != "=" then
              differences += s"invalid $label equals leaf slice: ${quoted(equalsLeaf.sourceSlice)}"
            if !byId.get(equalsLeaf.nodeId).exists(_.childIds.isEmpty) then
              differences += s"invalid $label equals leaf is not terminal"
          gapLeaves
            .filterNot(_.role == PhysicalPsiLeafRole.Equals)
            .foreach: leaf =>
              if leaf.role != PhysicalPsiLeafRole.Whitespace && leaf.role != PhysicalPsiLeafRole.Comment then
                differences += s"invalid $label nontrivia leaf around equals at ${show(leaf.range)}"
    differences.result()

  private def roleDifferences(
      node: PhysicalPsiNodeFact,
      byId: Map[Int, PhysicalPsiNodeFact],
      witness: ScalaPluginPhysicalPsiWitness
  ): Vector[String] =
    val differences = Vector.newBuilder[String]
    val intrinsic   = node.roles.filter(IntrinsicRoles).toSet
    val relational  = node.roles.filter(RelationalRoles)
    val additions   = node.roles.filterNot(IntrinsicRoles)
    val schema      = RoleSchemas.find(_.intrinsic == intrinsic)
    if node.roles.isEmpty then differences += s"missing role claims for node ${node.id}"
    if node.roles.distinct != node.roles then differences += s"duplicate role claims for node ${node.id}: ${node.roles}"
    if node.roles.sortBy(RoleOrder.indexOf) != node.roles then
      differences += s"noncanonical role order for node ${node.id}: ${node.roles}"
    if schema.isEmpty then differences += s"incompatible intrinsic role claims for node ${node.id}: ${node.roles}"
    schema.foreach: allowed =>
      additions
        .filterNot(allowed.additions)
        .foreach: role =>
          differences += s"role schema disallows $role on node ${node.id} with intrinsic roles $intrinsic"
    if relational.size > 1 then differences += s"incompatible relational role claims for node ${node.id}: ${node.roles}"
    additions.foreach:
      case PhysicalPsiNodeRole.DirectRhs          =>
        if node.id != 0 || node.parentId.nonEmpty || node.range != witness.rhsRange then
          differences += s"invalid direct-RHS relation on node ${node.id} at ${show(node.range)}"
      case PhysicalPsiNodeRole.InvokedExpression  =>
        val valid = node.parentId
          .flatMap(byId.get)
          .exists(parent =>
            parent.roles.contains(PhysicalPsiNodeRole.MethodCall) && parent.childIds.headOption.contains(node.id) &&
              contains(parent.range, node.range)
          )
        if !valid then differences += s"invalid invoked-expression relation on node ${node.id} at ${show(node.range)}"
      case PhysicalPsiNodeRole.NamedArgumentName  =>
        if !validNamedRelation(node, byId, witness, _.nameRange, first = true) then
          differences += s"invalid named-argument-name relation on node ${node.id} at ${show(node.range)}"
      case PhysicalPsiNodeRole.NamedArgumentValue =>
        if !validNamedRelation(node, byId, witness, _.valueRange, first = false) then
          differences += s"invalid named-argument-value relation on node ${node.id} at ${show(node.range)}"
      case _                                      => ()
    differences.result()

  private def validNamedRelation(
      node: PhysicalPsiNodeFact,
      byId: Map[Int, PhysicalPsiNodeFact],
      witness: ScalaPluginPhysicalPsiWitness,
      relatedRange: PhysicalPsiNamedArgumentFact => PcSourceRange,
      first: Boolean
  ): Boolean =
    node.parentId
      .flatMap(byId.get)
      .exists: parent =>
        val expectedChild = if first then parent.childIds.headOption else parent.childIds.lastOption
        parent.roles.contains(PhysicalPsiNodeRole.NamedArgument) && expectedChild.contains(node.id) &&
        contains(parent.range, node.range) && witness.namedArguments.exists(fact =>
          fact.argumentRange == parent.range && relatedRange(fact) == node.range
        )

  private def orderedDifference(label: String, expected: Vector[String], actual: Vector[String]): Vector[String] =
    val missing = expected.filterNot(actual.contains).map(value => s"missing $label: $value")
    val extra   = actual.filterNot(expected.contains).map(value => s"extra $label: $value")
    val changed =
      if missing.isEmpty && extra.isEmpty && expected != actual then
        Vector(s"reordered $label facts: expected ${expected.mkString(", ")}, found ${actual.mkString(", ")}")
      else Vector.empty
    missing ++ extra ++ changed

  private def contains(outer: PcSourceRange, inner: PcSourceRange): Boolean =
    outer.startOffset <= inner.startOffset && inner.endOffset <= outer.endOffset

  private def validRange(range: PcSourceRange, sourceLength: Int): Boolean =
    range.startOffset >= 0 && range.endOffset >= range.startOffset && range.endOffset <= sourceLength

  private def range(element: PsiElement): PcSourceRange =
    PcSourceRange(element.getTextRange.getStartOffset, element.getTextRange.getEndOffset)

  private def slice(source: String, range: PcSourceRange): String =
    if range.startOffset < 0 || range.endOffset < range.startOffset || range.endOffset > source.length then ""
    else source.substring(range.startOffset, range.endOffset)

  private def show(range: PcSourceRange): String = s"[${range.startOffset},${range.endOffset})"

  private def showPath(path: Vector[Int]): String =
    if path.isEmpty then "/" else path.mkString("/", "/", "")

  private def showNamed(value: PhysicalPsiNamedArgumentFact): String =
    s"argument=${show(value.argumentRange)}, name=${show(value.nameRange)}, equals=${show(value.equalsRange)}, value=${show(value.valueRange)}"

  private def quoted(value: String): String = value.replace("\n", "\\n").replace("\r", "\\r")

  private def message(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

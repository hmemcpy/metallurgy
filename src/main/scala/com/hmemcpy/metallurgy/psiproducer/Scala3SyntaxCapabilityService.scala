package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.{ParserSyntaxSnapshot, Scala3ParserArtifactCoordinate, Scala3ParserCompilerIdentity}
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.{VFileDeleteEvent, VFileEvent}
import com.intellij.openapi.vfs.{VfsUtilCore, VirtualFile, VirtualFileManager}

import java.util.HashMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[metallurgy] enum Scala3SyntaxCapabilityStage:
  case Module, Preparation, Parser, Evidence, RuntimeInventory, AggregateInventory, Catalog, Planner, Lexer, Emitter,
    HostInventory, PsiRoleBinding, Activation

private[metallurgy] enum Scala3SyntaxCapabilityState:
  case Preparing, Unavailable

private[metallurgy] enum Scala3SyntaxCapabilityOperation:
  case PrepareExactParser, BindPsiRoles, ActivateParser, ProduceWholeFilePsi

private[metallurgy] enum Scala3SyntaxCapabilityRequirement:
  case Capability(id: String)
  case GrammarRole(stableRole: Option[String])
  case OutputRole(stableRole: Option[String])

private[metallurgy] enum Scala3SyntaxRetainedOperation:
  case ReadVerbatimSource, EditVerbatimSource

private[metallurgy] enum Scala3SyntaxCapabilityEvidenceState:
  case Collecting, Recorded

private[metallurgy] enum Scala3SyntaxCapabilityRemediationState:
  case AwaitingPreparation, Retryable, ImplementationRequired

private[metallurgy] final case class Scala3SyntaxCapabilityScope(
    moduleName: String,
    file: Option[VirtualFile],
    operation: Scala3SyntaxCapabilityOperation
)

private[metallurgy] final case class Scala3SyntaxHostIdentity(
    ideBuild: String,
    scalaPluginId: String,
    scalaPluginVersion: Option[String]
)

private[metallurgy] final case class Scala3SyntaxCapabilityEvidence(
    state: Scala3SyntaxCapabilityEvidenceState,
    stage: Scala3SyntaxCapabilityStage,
    detail: String
)

private[metallurgy] final case class Scala3SyntaxCapabilityReport(
    state: Scala3SyntaxCapabilityState,
    compilerCoordinate: Option[Scala3ParserArtifactCoordinate],
    compilerIdentity: Option[Scala3ParserCompilerIdentity],
    hostIdentity: Scala3SyntaxHostIdentity,
    scope: Scala3SyntaxCapabilityScope,
    requirement: Scala3SyntaxCapabilityRequirement,
    retainedOperations: Vector[Scala3SyntaxRetainedOperation],
    evidence: Scala3SyntaxCapabilityEvidence,
    remediation: Scala3SyntaxCapabilityRemediationState,
    sourceDigest: Option[String],
    preparationEpoch: ParserPreparationEpoch
)

private[metallurgy] final case class Scala3SyntaxCapabilityFailure(
    sourceDigest: String,
    stage: Scala3SyntaxCapabilityStage,
    detail: String,
    preparationEpoch: ParserPreparationEpoch,
    compilerIdentity: Option[Scala3ParserCompilerIdentity],
    requirement: Scala3SyntaxCapabilityRequirement
)

private[metallurgy] object Scala3SyntaxCapabilityFailure:
  def from(
      sourceDigest: String,
      stage: Scala3SyntaxCapabilityStage,
      detail: Any,
      preparationEpoch: ParserPreparationEpoch,
      compilerIdentity: Option[Scala3ParserCompilerIdentity]
  ): Scala3SyntaxCapabilityFailure =
    Scala3SyntaxCapabilityFailure(
      sourceDigest,
      stage,
      detail.toString,
      preparationEpoch,
      compilerIdentity,
      requirement(stage, detail)
    )

  private def requirement(
      stage: Scala3SyntaxCapabilityStage,
      detail: Any
  ): Scala3SyntaxCapabilityRequirement = detail match
    case WholeFilePlanningFailure.UnprobedNativeCandidate(_, _, outputRoleId) =>
      Scala3SyntaxCapabilityRequirement.OutputRole(Some(outputRoleId.value))
    case WholeFilePlanningFailure.InvalidCatalog(errors)                      =>
      catalogRequirement(errors).getOrElse(stageRequirement(stage))
    case _: WholeFilePlanningFailure.UnknownOutputRealization                 =>
      Scala3SyntaxCapabilityRequirement.OutputRole(None)
    case errors: Vector[?]                                                    =>
      catalogRequirement(errors.collect { case error: CatalogValidationError => error })
        .getOrElse(stageRequirement(stage))
    case _                                                                    => stageRequirement(stage)

  private def catalogRequirement(
      errors: Vector[CatalogValidationError]
  ): Option[Scala3SyntaxCapabilityRequirement] =
    errors
      .collectFirst:
        case CatalogValidationError.UnknownGrammarRole(_, role)                   =>
          Scala3SyntaxCapabilityRequirement.GrammarRole(Some(role.value))
        case CatalogValidationError.CatalogAlternativeDerivedGrammarRole(_, role) =>
          Scala3SyntaxCapabilityRequirement.GrammarRole(Some(role.value))
        case CatalogValidationError.CompilerDerivedGrammarRole(_, role, _)        =>
          Scala3SyntaxCapabilityRequirement.GrammarRole(Some(role.value))
        case CatalogValidationError.UnreferencedGrammarRole(role)                 =>
          Scala3SyntaxCapabilityRequirement.GrammarRole(Some(role.value))
        case CatalogValidationError.UnrepresentedCatalogProduction(_, role)       =>
          Scala3SyntaxCapabilityRequirement.GrammarRole(Some(role.value))
      .orElse(
        errors.collectFirst:
          case CatalogValidationError.MissingDefaultOutputRole(_)            =>
            Scala3SyntaxCapabilityRequirement.OutputRole(None)
          case CatalogValidationError.UnknownOutputRole(_, _, role)          =>
            Scala3SyntaxCapabilityRequirement.OutputRole(Some(role.value))
          case CatalogValidationError.HostDerivedOutputRole(_, _, role, _)   =>
            Scala3SyntaxCapabilityRequirement.OutputRole(Some(role.value))
          case CatalogValidationError.UnreferencedOutputRole(role)           =>
            Scala3SyntaxCapabilityRequirement.OutputRole(Some(role.value))
          case CatalogValidationError.InvalidSurface(_, role, _, _)          =>
            Scala3SyntaxCapabilityRequirement.OutputRole(Some(role.value))
          case CatalogValidationError.InvalidSurfaceOwner(_, role, _, _)     =>
            Scala3SyntaxCapabilityRequirement.OutputRole(Some(role.value))
          case CatalogValidationError.IncompleteSurfaceStatus(_, role, _, _) =>
            Scala3SyntaxCapabilityRequirement.OutputRole(Some(role.value))
          case CatalogValidationError.UnaccountedSyntaxSurface(_)            =>
            Scala3SyntaxCapabilityRequirement.OutputRole(None)
      )

  private def stageRequirement(stage: Scala3SyntaxCapabilityStage): Scala3SyntaxCapabilityRequirement =
    stage match
      case Scala3SyntaxCapabilityStage.RuntimeInventory | Scala3SyntaxCapabilityStage.AggregateInventory |
          Scala3SyntaxCapabilityStage.Catalog | Scala3SyntaxCapabilityStage.Planner =>
        Scala3SyntaxCapabilityRequirement.GrammarRole(None)
      case Scala3SyntaxCapabilityStage.Lexer | Scala3SyntaxCapabilityStage.Emitter =>
        Scala3SyntaxCapabilityRequirement.OutputRole(None)
      case Scala3SyntaxCapabilityStage.PsiRoleBinding                              =>
        Scala3SyntaxCapabilityRequirement.Capability("psi-role-binding")
      case _                                                                       =>
        Scala3SyntaxCapabilityRequirement.Capability(s"exact-syntax-${stage.toString.toLowerCase}")

@Service(Array(Service.Level.PROJECT))
private[metallurgy] final class Scala3SyntaxCapabilityService(project: Project):
  private final case class Entry(module: Option[Module], failure: Scala3SyntaxCapabilityFailure)

  private val failures      = new HashMap[VirtualFile, Entry]
  private val moduleReports = new HashMap[Module, Scala3SyntaxCapabilityReport]

  project.getMessageBus
    .connect(project)
    .subscribe(
      VirtualFileManager.VFS_CHANGES,
      new BulkFileListener:
        override def after(events: java.util.List[? <: VFileEvent]): Unit =
          events.asScala.collect { case event: VFileDeleteEvent => event.getFile }.foreach(clearInvalid)
    )

  def publish(file: VirtualFile, failure: Scala3SyntaxCapabilityFailure): Unit = synchronized:
    publish(file, Option(ProjectFileIndex.getInstance(project).getModuleForFile(file)), failure)

  def publish(file: VirtualFile, module: Option[Module], failure: Scala3SyntaxCapabilityFailure): Unit = synchronized:
    if isCurrent(file, module, failure) then
      val entry = Entry(module, failure)
      if Option(failures.get(file)).forall(_ != entry) then
        val _ = failures.put(file, entry)
        MetallurgyStatus.publish(project, MetallurgyStatus.SyntaxCapability(report(file, module, failure)))

  def publishPreparing(module: Module, preparationEpoch: ParserPreparationEpoch): Unit = synchronized:
    val report = moduleReport(
      module,
      preparationEpoch,
      Scala3SyntaxCapabilityState.Preparing,
      Scala3SyntaxCapabilityOperation.PrepareExactParser,
      Scala3SyntaxCapabilityStage.Preparation,
      Scala3SyntaxCapabilityRequirement.Capability("exact-parser-preparation"),
      "exact parser artifacts and capabilities are being prepared",
      None,
      Scala3SyntaxCapabilityEvidenceState.Collecting,
      Scala3SyntaxCapabilityRemediationState.AwaitingPreparation
    )
    publishModule(module, report)

  def publishUnavailable(
      module: Module,
      preparationEpoch: ParserPreparationEpoch,
      operation: Scala3SyntaxCapabilityOperation,
      stage: Scala3SyntaxCapabilityStage,
      requirement: Scala3SyntaxCapabilityRequirement,
      detail: String,
      compilerIdentity: Option[Scala3ParserCompilerIdentity],
      remediation: Scala3SyntaxCapabilityRemediationState
  ): Unit = synchronized:
    val report = moduleReport(
      module,
      preparationEpoch,
      Scala3SyntaxCapabilityState.Unavailable,
      operation,
      stage,
      requirement,
      detail,
      compilerIdentity,
      Scala3SyntaxCapabilityEvidenceState.Recorded,
      remediation
    )
    publishModule(module, report)

  def resolve(
      file: VirtualFile,
      expected: Scala3SyntaxCapabilityFailure,
      compilerIdentity: Scala3ParserCompilerIdentity
  ): Unit = synchronized:
    Option(failures.get(file)) match
      case Some(entry @ Entry(_, failure))
          if failure == expected && isCurrent(file, entry.module, failure) &&
            failure.compilerIdentity.forall(_ == compilerIdentity) =>
        val _ = failures.remove(file)
        MetallurgyStatus.publish(
          project,
          MetallurgyStatus.SyntaxCapabilityResolved(report(file, entry.module, failure))
        )
      case _ => ()

  def resolve(module: Module, preparationEpoch: ParserPreparationEpoch): Unit = synchronized:
    Option(moduleReports.get(module)) match
      case Some(report)
          if report.preparationEpoch == preparationEpoch &&
            report.state == Scala3SyntaxCapabilityState.Preparing =>
        val _ = moduleReports.remove(module)
        MetallurgyStatus.publish(project, MetallurgyStatus.SyntaxCapabilityResolved(report))
      case _ => ()

  def discard(files: Iterable[VirtualFile]): Unit = synchronized:
    files.foreach: file =>
      val _ = failures.remove(file)

  def discard(module: Module): Unit = synchronized:
    val _     = moduleReports.remove(module)
    val files = failures
      .entrySet()
      .asScala
      .collect:
        case entry if entry.getValue.module.contains(module) => entry.getKey
    discard(files)

  def discardBefore(module: Module, preparationEpoch: ParserPreparationEpoch): Unit = synchronized:
    Option(moduleReports.get(module))
      .filter(_.preparationEpoch.value < preparationEpoch.value)
      .foreach(_ => moduleReports.remove(module))
    val files = failures
      .entrySet()
      .asScala
      .collect:
        case entry
            if entry.getValue.module.contains(module) &&
              entry.getValue.failure.preparationEpoch.value < preparationEpoch.value =>
          entry.getKey
    discard(files)

  def currentReports: Vector[Scala3SyntaxCapabilityReport] = synchronized:
    clearInvalidEntries()
    val fileReports = failures
      .entrySet()
      .asScala
      .toVector
      .map(entry => report(entry.getKey, entry.getValue.module, entry.getValue.failure))
    (moduleReports.values().asScala.toVector ++ fileReports).sortBy(report =>
      (
        report.scope.moduleName,
        report.scope.file.fold("")(_.getPresentableUrl),
        report.scope.operation.toString
      )
    )

  def currentFailures: Vector[Scala3SyntaxCapabilityReport] =
    currentReports.filter(_.state == Scala3SyntaxCapabilityState.Unavailable)

  def failureFor(file: VirtualFile, sourceDigest: String): Option[Scala3SyntaxCapabilityFailure] = synchronized:
    Option(failures.get(file))
      .filter(entry => isCurrent(file, entry.module, entry.failure))
      .map(_.failure)
      .filter(_.sourceDigest == sourceDigest)

  private def clearInvalid(deleted: VirtualFile): Unit = synchronized:
    val _ = failures.keySet().removeIf(file => file == deleted || !file.isValid)

  private def clearInvalidEntries(): Unit =
    val _ =
      failures.entrySet().removeIf(entry => !isCurrent(entry.getKey, entry.getValue.module, entry.getValue.failure))
    val _ = moduleReports.entrySet().removeIf(entry => !isCurrent(entry.getKey, entry.getValue))

  private def isCurrent(
      file: VirtualFile,
      module: Option[Module],
      failure: Scala3SyntaxCapabilityFailure
  ): Boolean =
    file.isValid && currentDigest(file).contains(failure.sourceDigest) && module.forall(value =>
      Scala3ParserPreparationLifecycle.get(project).stateFor(value).currentEpoch == failure.preparationEpoch
    )

  private def currentDigest(file: VirtualFile): Option[String] =
    try
      Option(FileDocumentManager.getInstance.getDocument(file))
        .map(document => ParserSyntaxSnapshot.digest(document.getImmutableCharSequence.toString))
        .orElse(Option.when(file.isValid)(ParserSyntaxSnapshot.digest(VfsUtilCore.loadText(file))))
    catch case NonFatal(_) => None

  private def report(
      file: VirtualFile,
      module: Option[Module],
      failure: Scala3SyntaxCapabilityFailure
  ): Scala3SyntaxCapabilityReport =
    val operation = failure.stage match
      case Scala3SyntaxCapabilityStage.Preparation | Scala3SyntaxCapabilityStage.Module =>
        Scala3SyntaxCapabilityOperation.PrepareExactParser
      case Scala3SyntaxCapabilityStage.PsiRoleBinding                                   =>
        Scala3SyntaxCapabilityOperation.BindPsiRoles
      case Scala3SyntaxCapabilityStage.Activation                                       =>
        Scala3SyntaxCapabilityOperation.ActivateParser
      case _                                                                            =>
        Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi
    Scala3SyntaxCapabilityReport(
      Scala3SyntaxCapabilityState.Unavailable,
      failure.compilerIdentity.map(_.coordinate).orElse(module.flatMap(configuredCompilerCoordinate)),
      failure.compilerIdentity,
      hostIdentity,
      Scala3SyntaxCapabilityScope(module.fold("<unavailable>")(_.getName), Some(file), operation),
      failure.requirement,
      Scala3SyntaxCapabilityService.RetainedOperations,
      Scala3SyntaxCapabilityEvidence(
        Scala3SyntaxCapabilityEvidenceState.Recorded,
        failure.stage,
        failure.detail
      ),
      Scala3SyntaxCapabilityRemediationState.ImplementationRequired,
      Some(failure.sourceDigest),
      failure.preparationEpoch
    )

  private def moduleReport(
      module: Module,
      preparationEpoch: ParserPreparationEpoch,
      state: Scala3SyntaxCapabilityState,
      operation: Scala3SyntaxCapabilityOperation,
      stage: Scala3SyntaxCapabilityStage,
      requirement: Scala3SyntaxCapabilityRequirement,
      detail: String,
      compilerIdentity: Option[Scala3ParserCompilerIdentity],
      evidenceState: Scala3SyntaxCapabilityEvidenceState,
      remediation: Scala3SyntaxCapabilityRemediationState
  ): Scala3SyntaxCapabilityReport =
    Scala3SyntaxCapabilityReport(
      state,
      compilerIdentity.map(_.coordinate).orElse(configuredCompilerCoordinate(module)),
      compilerIdentity,
      hostIdentity,
      Scala3SyntaxCapabilityScope(module.getName, None, operation),
      requirement,
      Scala3SyntaxCapabilityService.RetainedOperations,
      Scala3SyntaxCapabilityEvidence(evidenceState, stage, detail),
      remediation,
      None,
      preparationEpoch
    )

  private def publishModule(module: Module, report: Scala3SyntaxCapabilityReport): Unit =
    Option(moduleReports.get(module)) match
      case Some(current) if current == report || current.preparationEpoch.value > report.preparationEpoch.value =>
        ()
      case _                                                                                                    =>
        val _ = moduleReports.put(module, report)
        MetallurgyStatus.publish(project, MetallurgyStatus.SyntaxCapability(report))

  private def isCurrent(module: Module, report: Scala3SyntaxCapabilityReport): Boolean =
    !module.isDisposed && (Scala3ParserPreparationLifecycle.get(project).stateFor(module) match
      case ParserPreparationState.Preparing(epoch)      =>
        report.state == Scala3SyntaxCapabilityState.Preparing && report.preparationEpoch == epoch
      case ParserPreparationState.Unavailable(epoch, _) =>
        report.state == Scala3SyntaxCapabilityState.Unavailable && report.preparationEpoch == epoch
      case _                                            => false
    )

  private def configuredCompilerCoordinate(module: Module): Option[Scala3ParserArtifactCoordinate] =
    Option(ScalaPluginSemanticBridge.getScalaVersion(module))
      .filter(_.nonEmpty)
      .map(Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", _))

  private lazy val hostIdentity: Scala3SyntaxHostIdentity =
    val pluginId   = PluginId.getId("org.intellij.scala")
    val descriptor = Option(PluginManagerCore.getPlugin(pluginId))
    Scala3SyntaxHostIdentity(
      ApplicationInfo.getInstance.getBuild.asString,
      pluginId.getIdString,
      descriptor.flatMap(value => Option(value.getVersion).filter(_.nonEmpty))
    )

private[metallurgy] object Scala3SyntaxCapabilityService:
  val RetainedOperations: Vector[Scala3SyntaxRetainedOperation] =
    Vector(
      Scala3SyntaxRetainedOperation.ReadVerbatimSource,
      Scala3SyntaxRetainedOperation.EditVerbatimSource
    )

  def get(project: Project): Scala3SyntaxCapabilityService = project.getService(classOf[Scala3SyntaxCapabilityService])

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserSourceUri, ParserSyntaxSnapshot, Scala3ParserCancellation, Scala3ParserRequest}
import com.intellij.lang.{ASTNode, PsiBuilderFactory}
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.{StandardFileSystems, VirtualFile}
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.stubs.ScFileStub

final class Scala3DotcFileElementType
    extends IStubFileElementType[ScFileStub](Scala3DotcFileElementType.DebugName, Scala3DotcLanguage.INSTANCE):

  override def getExternalId: String = Scala3DotcFileElementType.ExternalId

  override def getStubVersion: Int =
    Math.addExact(Scala3ParserDefinition.FileNodeType.getStubVersion, Scala3DotcFileElementType.SchemaVersion)

  override def shouldBuildStubFor(file: VirtualFile): Boolean =
    file.getFileSystem.getProtocol != StandardFileSystems.JAR_PROTOCOL

  override def getBuilder: DefaultStubBuilder = new DefaultStubBuilder:
    override protected def createStubForFile(file: PsiFile): PsiFileStubImpl[? <: PsiFile] =
      file.getViewProvider.getPsi(getLanguage) match
        case scalaFile: ScalaFile => new Scala3DotcFileStub(scalaFile, Scala3DotcFileElementType.this)
        case _                    => PsiFileStubImpl(file)

  override def serialize(stub: ScFileStub, dataStream: StubOutputStream): Unit = ()

  override def deserialize(
      dataStream: StubInputStream,
      parentStub: StubElement[? <: PsiElement]
  ): ScFileStub = new Scala3DotcFileStub(null, this)

  override def indexStub(stub: ScFileStub, sink: IndexSink): Unit = ()

  override protected def doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode =
    val source                                                                                  = chameleon.getChars.toString
    val module                                                                                  = Option(ModuleUtilCore.findModuleForPsiElement(psi))
      .orElse(
        Option(psi.getContainingFile)
          .flatMap(file => Option(file.getVirtualFile))
          .flatMap(file => Option(ModuleUtilCore.findModuleForFile(file, psi.getProject)))
      )
      .orElse(
        Option(psi.getContainingFile)
          .flatMap(file => Option(file.getViewProvider))
          .collect { case provider: Scala3DotcFileViewProvider =>
            provider.module
          }
          .flatten
      )
    val sourceFile                                                                              = Option(psi.getContainingFile)
      .filter(_.getViewProvider.isEventSystemEnabled)
      .flatMap(file => Option(file.getVirtualFile))
    val sourceId                                                                                = sourceUri(psi, digest = ParserSyntaxSnapshot.digest(source))
    val digest                                                                                  = ParserSyntaxSnapshot.digest(source)
    val lifecycle                                                                               = Scala3ParserPreparationLifecycle.get(psi.getProject)
    val preparationEpoch                                                                        = module
      .map(value => lifecycle.stateFor(value).currentEpoch)
      .getOrElse(ParserPreparationEpoch.Disposed)
    val capabilityService                                                                       = Scala3SyntaxCapabilityService
      .get(psi.getProject)
    val priorFailure                                                                            = sourceFile.flatMap(file => capabilityService.failureFor(file, digest))
    def failure(stage: Scala3SyntaxCapabilityStage, detail: Any): Scala3SyntaxCapabilityFailure =
      Scala3SyntaxCapabilityFailure.from(digest, stage, detail, preparationEpoch, None)
    val planned                                                                                 = for
      active         <- module.toRight(failure(Scala3SyntaxCapabilityStage.Module, "source module is unavailable"))
      prepared       <- lifecycle
                          .parserFor(active)
                          .toRight(failure(Scala3SyntaxCapabilityStage.Preparation, lifecycle.stateFor(active)))
      uri            <- ParserSourceUri.from(sourceId).left.map(failure(Scala3SyntaxCapabilityStage.Parser, _))
      snapshot       <- prepared.bridge
                          .parse(
                            Scala3ParserRequest(
                              uri,
                              source,
                              prepared.compilerOptions,
                              new Scala3ParserCancellation:
                                override def checkCanceled(): Unit = ProgressManager.checkCanceled()
                            )
                          )
                          .left
                          .map(failure(Scala3SyntaxCapabilityStage.Parser, _))
      snapshotFailure = (stage: Scala3SyntaxCapabilityStage, detail: Any) =>
                          Scala3SyntaxCapabilityFailure.from(
                            digest,
                            stage,
                            detail,
                            preparationEpoch,
                            Some(snapshot.compilerIdentity)
                          )
      evidence       <- ProvisionalSourceEvidencePlanner
                          .plan(snapshot)
                          .left
                          .map(snapshotFailure(Scala3SyntaxCapabilityStage.Evidence, _))
      runtime        <- CompilerRuntimeInventory
                          .from(snapshot)
                          .left
                          .map(snapshotFailure(Scala3SyntaxCapabilityStage.RuntimeInventory, _))
      aggregate      <- AggregatedCompilerProductionInventory
                          .aggregate(Vector(runtime))
                          .left
                          .map(snapshotFailure(Scala3SyntaxCapabilityStage.AggregateInventory, _))
      catalog        <- PreparedProductionCatalog
                          .prepareRuntimeSubset(prepared.catalog, runtime, aggregate, prepared.surfaces)
                          .left
                          .map(snapshotFailure(Scala3SyntaxCapabilityStage.Catalog, _))
      plan           <- WholeFileProductionPlanner
                          .plan(snapshot, evidence, catalog)
                          .left
                          .map(snapshotFailure(Scala3SyntaxCapabilityStage.Planner, _))
      lexer          <- PlannedScala3Lexer
                          .compile(source, plan, prepared.bindings)
                          .left
                          .map(snapshotFailure(Scala3SyntaxCapabilityStage.Lexer, _))
    yield (plan, prepared.bindings, lexer, snapshot.compilerIdentity)
    val lexer                                                                                   = planned.fold(_ => PlannedScala3Lexer.closed, _._3)
    val builder                                                                                 = PsiBuilderFactory
      .getInstance()
      .createBuilder(psi.getProject, chameleon, lexer, Scala3DotcLanguage.INSTANCE, chameleon.getChars)
    val emitted                                                                                 = planned.flatMap: (plan, bindings, _, compilerIdentity) =>
      DotcPsiProducer
        .parseResult(this, builder, plan, bindings)
        .left
        .map(detail =>
          Scala3SyntaxCapabilityFailure.from(
            digest,
            Scala3SyntaxCapabilityStage.Emitter,
            detail,
            preparationEpoch,
            Some(compilerIdentity)
          )
        )
    emitted match
      case Right(_)     =>
        sourceFile.foreach(file =>
          planned.foreach((_, _, _, compilerIdentity) =>
            priorFailure.foreach(capabilityService.resolve(file, _, compilerIdentity))
          )
        )
      case Left(reason) =>
        sourceFile.foreach(file => capabilityService.publish(file, module, reason))
        DotcPsiProducer.emitClosedFile(this, builder)
    builder.getTreeBuilt.getFirstChildNode

  private def sourceUri(psi: PsiElement, digest: String): String =
    Option(psi.getContainingFile)
      .flatMap(file => Option(file.getVirtualFile))
      .map(_.getUrl)
      .getOrElse(s"file:///metallurgy/Detached-$digest-${System.identityHashCode(psi.getContainingFile)}.scala")

private[metallurgy] object Scala3DotcFileElementType:
  val ExternalId                     = "metallurgy.scala3.file"
  val DebugName                      = "METALLURGY_SCALA3_FILE"
  val SchemaVersion                  = 5
  lazy val SchemaFingerprint: String =
    Scala3PsiProductionCatalog.persistenceSchemaFingerprint(Scala3PsiProductionCatalog.Reviewed)

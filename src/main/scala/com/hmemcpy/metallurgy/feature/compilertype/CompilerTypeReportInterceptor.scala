package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.compilerbackend.{ScalaCompilerMessage, ScalaPluginSemanticBridge}
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.{
  PsiDocumentManager,
  PsiElement,
  PsiFile,
  PsiManager,
  SmartPointerManager,
  SmartPsiElementPointer
}
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

import java.nio.file.Path

final class CompilerTypeReportInterceptor(project: Project) extends Disposable:

  private val Log          = Logger.getInstance("com.hmemcpy.metallurgy.CompilerTypeReportInterceptor")
  private val reapplyAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  subscribeToCompilerEvents()

  private def subscribeToCompilerEvents(): Unit =
    try
      ScalaPluginSemanticBridge.subscribeToCompilerMessages(project, this)(onCompilerMessage)
      Log.info("CompilerTypeReportInterceptor: subscribed to compiler events")
    catch case e: Exception => Log.error("CompilerTypeReportInterceptor: failed to subscribe", e)

  private def onCompilerMessage(message: ScalaCompilerMessage): Unit =
    CompilerTypeReport
      .from(message)
      .foreach: report =>
        ReadAction
          .nonBlocking(() => renderInput(report))
          .inSmartMode(project)
          .expireWith(this)
          .submit(AppExecutorUtil.getAppExecutorService)
          .onSuccess(_.foreach(intercept))

  private def intercept(input: RenderInput): Unit =
    try
      MetallurgyStatus.publish(project, MetallurgyStatus.Resolving(input.module.getName))
      PcSessionManager.get(project).sessionFor(input.module) match
        case Some(session) =>
          TypeRenderer.render(session, input.snapshot, input.range) match
            case Some(pcType) => replaceReportedType(input, pcType)
            case None         => MetallurgyStatus.publish(project, MetallurgyStatus.NoType(input.module.getName))
        case None          =>
          MetallurgyStatus.publish(project, MetallurgyStatus.Unavailable(input.module.getName))
    catch
      case e: Exception =>
        Log.warn(s"CompilerTypeReportInterceptor: failed to intercept report: ${e.getMessage}", e)

  private def renderInput(report: CompilerTypeReport): Option[RenderInput] =
    for
      virtualFile <- Option(LocalFileSystem.getInstance.findFileByNioFile(report.path))
      psiFile     <- Option(PsiManager.getInstance(project).findFile(virtualFile))
      document    <- Option(PsiDocumentManager.getInstance(project).getDocument(psiFile))
      range       <- report.toTextRange(psiFile)
      element     <- exactElement(psiFile, range)
      module      <- Option(ModuleUtilCore.findModuleForPsiElement(element))
      if ModuleDetectionService.get(project).isActive(module)
    yield RenderInput(
      module,
      SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
      PcSnapshot(virtualFile.getUrl, document.getModificationStamp, document.getText),
      range,
      report.nativeType
    )

  private def exactElement(file: PsiFile, range: TextRange): Option[PsiElement] =
    Option(file.findElementAt(range.getStartOffset)).flatMap { leaf =>
      Iterator
        .iterate(leaf)(_.getParent)
        .takeWhile(_ != null)
        .find(_.getTextRange == range)
    }

  private def replaceReportedType(input: RenderInput, pcType: String): Unit =
    ReapplyDelays.foreach { delay =>
      reapplyAlarm.addRequest(
        () =>
          input.element.getElement match
            case null    => ()
            case element =>
              ScalaPluginSemanticBridge.setCompilerType(element, pcType)
              ScalaPluginSemanticBridge.clearScalaTypeCaches(project, element),
        delay
      )
    }
    MetallurgyStatus.publish(project, MetallurgyStatus.Resolved(input.module.getName, pcType))
    Log.info(s"CompilerTypeReportInterceptor: replaced compiler type '${input.nativeType}' with '$pcType'")

  override def dispose(): Unit = ()

  private val ReapplyDelays = Seq(0, 100, 300, 750, 1500, 3000)

private final case class RenderInput(
    module: Module,
    element: SmartPsiElementPointer[PsiElement],
    snapshot: PcSnapshot,
    range: TextRange,
    nativeType: String
)

private final case class Position(line: Int, column: Int)

private final case class CompilerTypeReport(path: Path, begin: Position, end: Position, nativeType: String):
  def toTextRange(file: PsiFile): Option[TextRange] =
    Option(PsiDocumentManager.getInstance(file.getProject).getDocument(file)).flatMap { document =>
      def offset(position: Position): Option[Int] =
        Option.when(position.line > 0 && position.line <= document.getLineCount) {
          document.getLineStartOffset(position.line - 1) + position.column - 1
        }

      for
        start  <- offset(begin)
        finish <- offset(end)
        if start >= 0 && finish >= start && finish <= document.getTextLength
      yield TextRange(start, finish)
    }

private object CompilerTypeReport:
  val TypePrefix         = "<type>"
  private val TypeSuffix = "</type>"

  def typeFrom(text: String): Option[String] =
    val suffix = text.indexOf(TypeSuffix)
    Option.when(suffix >= TypePrefix.length)(text.substring(TypePrefix.length, suffix))

  def from(message: ScalaCompilerMessage): Option[CompilerTypeReport] =
    Option
      .when(message.text.startsWith(TypePrefix)):
        typeFrom(message.text).map: nativeType =>
          CompilerTypeReport(
            message.path,
            Position(message.beginLine, message.beginColumn),
            Position(message.endLine, message.endColumn),
            nativeType
          )
      .flatten

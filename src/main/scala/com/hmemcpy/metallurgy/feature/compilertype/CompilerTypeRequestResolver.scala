package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{PcSession, PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState, ReadAction}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiDocumentManager, PsiElement, SmartPointerManager, SmartPsiElementPointer}
import com.intellij.util.concurrency.AppExecutorUtil

import scala.util.control.NonFatal

/** Answers the bundled Scala plugin's compiler-type requests without blocking its caller thread.
  *
  * The bundled plugin requests the compiler type only during completion
  * (`org.jetbrains.plugins.scala.lang.completion`), then reads the stored slot for expression and reference type
  * resolution. Filling the slot here makes the presentation compiler's type available to those lookups; the
  * [[com.hmemcpy.metallurgy.feature.inlay.PcTypeHintsPass]] is the proactive surface that shows types without depending
  * on the completion trigger.
  */
final class CompilerTypeRequestResolver(project: Project) extends Disposable:

  private val log = Logger.getInstance(classOf[CompilerTypeRequestResolver])

  subscribe()

  private def subscribe(): Unit =
    try
      ScalaPluginSemanticBridge.subscribeToCompilerTypeRequests(project, this)(request)
      log.info("Subscribed to Scala CompilerType requests")
    catch case error: Exception => log.error("Could not subscribe to Scala CompilerType requests", error)

  private[metallurgy] def request(element: PsiElement): Unit =
    requestFor(element).foreach: request =>
      MetallurgyStatus.publish(project, MetallurgyStatus.Resolving(request.module.getName))
      PcSessionManager
        .get(project)
        .sessionForAsync(request.module)
        .whenComplete: (session, error) =>
          if error != null then finish(request, failed(error))
          else
            session match
              case Some(value) => query(request, value)
              case None        => finish(request, TypeResolution.Unavailable)

  private def query(request: TypeRequest, session: PcSession): Unit =
    if project.isDisposed then return
    try
      val _ = ReadAction
        .nonBlocking(() => resolve(request, session))
        .inSmartMode(project)
        .expireWith(this)
        .finishOnUiThread(
          ModalityState.defaultModalityState(),
          result => applyResult(request, result)
        )
        .submit(AppExecutorUtil.getAppExecutorService)
    catch case NonFatal(error) => finish(request, failed(error))

  private def requestFor(element: PsiElement): Option[TypeRequest] =
    for
      module       <- Option(ModuleUtilCore.findModuleForPsiElement(element))
      if ModuleDetectionService.get(project).isActive(module)
      file         <- Option(element.getContainingFile)
      virtualFile  <- Option(file.getVirtualFile)
      document     <- Option(PsiDocumentManager.getInstance(project).getDocument(file))
      elementRange <- Option(element.getTextRange)
    yield TypeRequest(
      module,
      SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
      PcSnapshot(virtualFile.getUrl, document.getModificationStamp, document.getText),
      elementRange
    )

  private def resolve(request: TypeRequest, session: PcSession): TypeResolution =
    try
      TypeRenderer.render(session, request.snapshot, request.range) match
        case Some(value) => TypeResolution.Resolved(value)
        case None        => TypeResolution.NoType
    catch
      case NonFatal(error) =>
        log.warn(s"Compiler type request failed for ${request.module.getName}", error)
        failed(error)

  private def failed(error: Throwable): TypeResolution.Failed =
    TypeResolution.Failed(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  private def finish(request: TypeRequest, result: TypeResolution): Unit =
    if !project.isDisposed then
      ApplicationManager.getApplication.invokeLater(
        () => if !project.isDisposed then applyResult(request, result),
        ModalityState.defaultModalityState()
      )

  private def applyResult(request: TypeRequest, result: TypeResolution): Unit =
    val moduleName = request.module.getName
    result match
      case TypeResolution.Resolved(value) =>
        Option(request.element.getElement)
          .filter(isCurrent(request, _))
          .foreach: element =>
            ScalaPluginSemanticBridge.setCompilerType(element, value)
            ScalaPluginSemanticBridge.clearScalaTypeCaches(project, element)
            MetallurgyStatus.publish(project, MetallurgyStatus.Resolved(moduleName, value))
            log.debug(s"Set compiler type '$value' in $moduleName")
      case TypeResolution.NoType          =>
        MetallurgyStatus.publish(project, MetallurgyStatus.NoType(moduleName))
      case TypeResolution.Unavailable     =>
        MetallurgyStatus.publish(project, MetallurgyStatus.Unavailable(moduleName))
      case TypeResolution.Failed(message) =>
        MetallurgyStatus.publish(project, MetallurgyStatus.Failed(moduleName, message))

  private def isCurrent(request: TypeRequest, element: PsiElement): Boolean =
    Option(element.getContainingFile)
      .flatMap(file => Option(PsiDocumentManager.getInstance(project).getDocument(file)))
      .exists(_.getModificationStamp == request.snapshot.documentVersion)

  override def dispose(): Unit = ()

private final case class TypeRequest(
    module: Module,
    element: SmartPsiElementPointer[PsiElement],
    snapshot: PcSnapshot,
    range: com.intellij.openapi.util.TextRange
)

private enum TypeResolution:
  case Resolved(value: String)
  case NoType
  case Unavailable
  case Failed(message: String)

object CompilerTypeRequestResolver:
  def apply(project: Project): CompilerTypeRequestResolver = project.getService(classOf[CompilerTypeRequestResolver])

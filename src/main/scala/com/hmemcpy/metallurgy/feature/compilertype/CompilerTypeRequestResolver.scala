package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiElement, SmartPointerManager, SmartPsiElementPointer}

/** Turns the bundled Scala plugin's compiler-type requests into nonblocking demand signals for whole-file population.
  *
  * The bundled plugin requests the compiler type only during completion
  * (`org.jetbrains.plugins.scala.lang.completion`), then reads the stored slot for expression and reference type
  * resolution. The dedicated semantic pass normally populates that slot proactively; this subscriber schedules the same
  * coalesced population when a request arrives first. It never performs a separate per-offset compiler query.
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
        .prepareCompilerBackend(request.file)
        .whenComplete: (session, error) =>
          if error != null then finish(request, failed(error))
          else if session.nonEmpty then finish(request, TypeResolution.Prepared)
          else finish(request, TypeResolution.Unavailable)

  private def requestFor(element: PsiElement): Option[TypeRequest] =
    for
      module      <- Option(ModuleUtilCore.findModuleForPsiElement(element))
      if ModuleDetectionService.get(project).isActive(module)
      file        <- Option(element.getContainingFile)
      virtualFile <- Option(file.getVirtualFile)
      document    <- Option(PsiDocumentManager.getInstance(project).getDocument(file))
    yield TypeRequest(
      module,
      SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
      virtualFile,
      document.getModificationStamp
    )

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
      case TypeResolution.Prepared        =>
        Option(request.element.getElement)
          .filter(isCurrent(request, _))
          .flatMap(element => Option(ScalaPluginSemanticBridge.getCompilerType(element)).filter(_.nonEmpty)) match
          case Some(value) =>
            MetallurgyStatus.publish(project, MetallurgyStatus.Resolved(moduleName, value))
            log.debug(s"Compiler type '$value' is current in $moduleName")
          case None        => MetallurgyStatus.publish(project, MetallurgyStatus.NoType(moduleName))
      case TypeResolution.Unavailable     =>
        MetallurgyStatus.publish(project, MetallurgyStatus.Unavailable(moduleName))
      case TypeResolution.Failed(message) =>
        MetallurgyStatus.publish(project, MetallurgyStatus.Failed(moduleName, message))

  private def isCurrent(request: TypeRequest, element: PsiElement): Boolean =
    Option(element.getContainingFile)
      .flatMap(file => Option(PsiDocumentManager.getInstance(project).getDocument(file)))
      .exists(_.getModificationStamp == request.documentVersion)

  override def dispose(): Unit = ()

private final case class TypeRequest(
    module: Module,
    element: SmartPsiElementPointer[PsiElement],
    file: VirtualFile,
    documentVersion: Long
)

private enum TypeResolution:
  case Prepared
  case Unavailable
  case Failed(message: String)

object CompilerTypeRequestResolver:
  def apply(project: Project): CompilerTypeRequestResolver = project.getService(classOf[CompilerTypeRequestResolver])

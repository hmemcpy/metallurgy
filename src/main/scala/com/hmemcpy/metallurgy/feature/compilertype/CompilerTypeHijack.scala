package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.module.{BundledPluginBridge, ModuleDetectionService}
import com.hmemcpy.metallurgy.pc.{PcSession, PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState, ReadAction}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiDocumentManager, PsiElement, SmartPointerManager, SmartPsiElementPointer}
import com.intellij.util.concurrency.AppExecutorUtil

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import scala.util.control.NonFatal

/** Answers the bundled Scala plugin's compiler-type requests without blocking its caller thread. */
final class CompilerTypeHijack(project: Project) extends Disposable:

  private val log = Logger.getInstance(classOf[CompilerTypeHijack])

  subscribe()

  private def subscribe(): Unit =
    try
      val listenerClass = BundledPluginBridge.listenerClass
      val handler       = new InvocationHandler:
        override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
          if method.getName == "onCompilerTypeRequest" && args != null && args.nonEmpty then
            schedule(args(0).asInstanceOf[PsiElement])
          null

      val listener = Proxy.newProxyInstance(listenerClass.getClassLoader, Array(listenerClass), handler)
      project.getMessageBus
        .connect(this)
        .subscribe(
          BundledPluginBridge.compilerTypeTopic.asInstanceOf[com.intellij.util.messages.Topic[AnyRef]],
          listener.asInstanceOf[AnyRef]
        )
      log.info("Subscribed to Scala CompilerType requests")
    catch case error: Exception => log.error("Could not subscribe to Scala CompilerType requests", error)

  private def schedule(element: PsiElement): Unit =
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
      if ModuleDetectionService.get(project).isEligible(module)
      if MetallurgySettings(project).isEnabled(module)
      if BundledPluginBridge.usesCompilerTypes(project)
      file         <- Option(element.getContainingFile)
      virtualFile  <- Option(file.getVirtualFile)
      document     <- Option(PsiDocumentManager.getInstance(project).getDocument(file))
      elementRange <- Option(element.getTextRange)
    yield TypeRequest(
      module,
      SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
      PcSnapshot(virtualFile.getUrl, document.getModificationStamp, document.getText),
      elementRange.getStartOffset
    )

  private def resolve(request: TypeRequest, session: PcSession): TypeResolution =
    try
      TypeRenderer.render(session, request.snapshot, request.offset) match
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
            BundledPluginBridge.setCompilerType(element, value)
            BundledPluginBridge.clearScalaTypeCaches(project, element)
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
    offset: Int
)

private enum TypeResolution:
  case Resolved(value: String)
  case NoType
  case Unavailable
  case Failed(message: String)

object CompilerTypeHijack:
  def apply(project: Project): CompilerTypeHijack = project.getService(classOf[CompilerTypeHijack])

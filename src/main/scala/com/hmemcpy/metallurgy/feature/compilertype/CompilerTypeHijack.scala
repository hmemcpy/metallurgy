package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.module.{BundledPluginBridge, ModuleDetectionService}
import com.hmemcpy.metallurgy.pc.{PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.util.messages.MessageBusConnection

import java.lang.reflect.{InvocationHandler, Method, Proxy}

final class CompilerTypeHijack(project: Project) extends Disposable:

  private val Log = Logger.getInstance("com.hmemcpy.metallurgy.CompilerTypeHijack")

  Log.info("CompilerTypeHijack: constructor started")

  try
    val connection: MessageBusConnection = project.getMessageBus.connect(this)
    val topic                            = BundledPluginBridge.compilerTypeTopic
    val listenerClass                    = BundledPluginBridge.listenerClass
    Log.info(s"CompilerTypeHijack: topic=$topic, listenerClass=$listenerClass")

    val handler = new InvocationHandler:
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
        if method.getName == "onCompilerTypeRequest" && args != null && args.nonEmpty then
          handleRequest(args(0).asInstanceOf[PsiElement])
        null

    val listener = Proxy.newProxyInstance(listenerClass.getClassLoader, Array(listenerClass), handler)
    connection.subscribe(topic.asInstanceOf[com.intellij.util.messages.Topic[AnyRef]], listener.asInstanceOf[AnyRef])
    Log.info("CompilerTypeHijack: subscribed to CompilerType.Topic")
  catch
    case e: Exception =>
      Log.error("CompilerTypeHijack: failed to subscribe", e)

  private def handleRequest(element: PsiElement): Unit =
    try
      val module = ModuleUtilCore.findModuleForPsiElement(element)
      if module == null then return
      if !ModuleDetectionService.get(project).isEligible(module) then return
      if !MetallurgySettings(project).isEnabled(module) then return
      if !BundledPluginBridge.usesCompilerTypes(project) then return

      Log.info(
        s"CompilerTypeHijack: request for element at offset ${element.getTextRange.getStartOffset} in ${module.getName}"
      )

      val existing = BundledPluginBridge.getCompilerType(element)
      if existing != null then Log.info(s"CompilerTypeHijack: replacing existing compiler type '$existing'")

      val file = element.getContainingFile
      if file == null || file.getVirtualFile == null then return

      val snapshot   = PcSnapshot.forFile(file.getVirtualFile, file.getModificationStamp)
      val offset     = element.getTextRange.getStartOffset
      val moduleName = module.getName

      MetallurgyStatus.publish(project, MetallurgyStatus.Resolving(moduleName))
      PcSessionManager.get(project).sessionFor(module) match
        case Some(session) =>
          TypeRenderer.render(session, snapshot, offset) match
            case Some(tpe) =>
              BundledPluginBridge.setCompilerType(element, tpe)
              MetallurgyStatus.publish(project, MetallurgyStatus.Resolved(moduleName, tpe))
              Log.info(s"CompilerTypeHijack: set type '$tpe'")
            case None      =>
              MetallurgyStatus.publish(project, MetallurgyStatus.NoType(moduleName))
              Log.info("CompilerTypeHijack: pc returned no type")
        case None          =>
          MetallurgyStatus.publish(project, MetallurgyStatus.Unavailable(moduleName))
          Log.info(s"CompilerTypeHijack: no pc session for $moduleName")
    catch
      case e: Exception =>
        val module     = ModuleUtilCore.findModuleForPsiElement(element)
        val moduleName = Option(module).fold("unknown module")(_.getName)
        MetallurgyStatus.publish(
          project,
          MetallurgyStatus.Failed(moduleName, Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
        )
        Log.warn(s"CompilerTypeHijack: error handling request: ${e.getMessage}")

  override def dispose(): Unit = {}

object CompilerTypeHijack:
  def apply(project: Project): CompilerTypeHijack = project.getService(classOf[CompilerTypeHijack])

package org.virtuslab.ideprobe

import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.wm.impl.status.IdeStatusBarImpl
import com.intellij.openapi.wm.{StatusBarWidget, WindowManager}
import com.intellij.psi.PsiDocumentManager

import org.virtuslab.ideprobe.handlers.{IntelliJApi, PSI, Projects}
import org.virtuslab.ideprobe.protocol.FileRef

object MetallurgyStatus extends IntelliJApi {
  private val PluginIdValue = PluginId.getId("com.hmemcpy.metallurgy")

  def inspect(fileRef: FileRef): Map[String, String] = {
    val project = Projects.resolve(fileRef.project)
    val loader = Option(PluginManagerCore.getPlugin(PluginIdValue))
      .map(_.getPluginClassLoader)
      .getOrElse(error("Metallurgy plugin is not loaded"))
    val settingsClass = Class.forName("com.hmemcpy.metallurgy.settings.MetallurgySettings", true, loader)
    val lifecycleClass = Class.forName(
      "com.hmemcpy.metallurgy.psiproducer.Scala3ParserPreparationLifecycle",
      true,
      loader
    )
    val settings = project.getService(settingsClass.asInstanceOf[Class[AnyRef]])
    val lifecycle = project.getService(lifecycleClass.asInstanceOf[Class[AnyRef]])
    val globallyEnabled = invoke(settingsClass, settings, "isGloballyEnabled").toString
    val stateFor = lifecycleClass.getMethod("stateFor", classOf[com.intellij.openapi.module.Module])
    val moduleStates = ModuleManager
      .getInstance(project)
      .getModules
      .toVector
      .map(module => s"module.${module.getName}" -> stateFor.invoke(lifecycle, module).toString)
    val language = read(PSI.resolve(fileRef).getLanguage.getDisplayName)
    val compatibility = if (moduleStates.exists(_._2.startsWith("Ready("))) compatibleIntegerLiteral(project, loader)
    else Vector.empty
    (Vector("globallyEnabled" -> globallyEnabled, "language" -> language) ++
      syntaxWidget(project) ++ compatibility ++ moduleStates).toMap
  }

  def replaceWithSupportedSyntax(fileRef: FileRef): Map[String, String] = {
    val project = Projects.resolve(fileRef.project)
    val file = PSI.resolve(fileRef)
    val document = Option(PsiDocumentManager.getInstance(project).getDocument(file))
      .getOrElse(error(s"Document is absent for ${fileRef.path}"))
    onEdt {
      WriteCommandAction.runWriteCommandAction(
        project,
        new Runnable {
          override def run(): Unit =
            document.setText("package dogfood.showcase\n\nimport scala.collection.*\n")
        }
      )
      PsiDocumentManager.getInstance(project).commitDocument(document)
      FileDocumentManager.getInstance().saveDocument(document)
    }
    inspect(fileRef)
  }

  private def compatibleIntegerLiteral(project: AnyRef, loader: ClassLoader): Vector[(String, String)] = {
    val compatibleClass = "org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyIntegerLiteral"
    val loadedBefore = hasLoadedClass(loader, compatibleClass)
    val bridgeClass = Class.forName(
      "com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge$",
      true,
      loader
    )
    val bridge = bridgeClass.getField("MODULE$").get(null)
    val compatibleProbe = bridgeClass.getMethods
      .find(method => method.getName == "probeCompatibleIntegerLiterals" && method.getParameterCount == 1)
      .getOrElse(error("Compatible integer literal probe is absent"))
      .invoke(bridge, project)
    val loadedAfter = hasLoadedClass(loader, compatibleClass)
    Vector(
      "compatibleIntegerLiteral.loadedBeforeProbe" -> loadedBefore.toString,
      "compatibleIntegerLiteral.loadedAfterProbe" -> loadedAfter.toString,
      "compatibleIntegerLiteral.probe" -> compatibleProbe.toString
    )
  }

  private def syntaxWidget(project: com.intellij.openapi.project.Project): Vector[(String, String)] = onEdt {
    val statusBar = Option(WindowManager.getInstance().getStatusBar(project))
    val widget = statusBar.flatMap(value => Option(value.getWidget("Metallurgy")))
    val presentation = widget.flatMap(value =>
      Option(value.getPresentation).collect { case text: StatusBarWidget.TextPresentation => text }
    )
    val component = statusBar.collect { case value: IdeStatusBarImpl => value }.flatMap(value =>
      Option(value.getWidgetComponent("Metallurgy"))
    )
    Vector(
      "syntaxWidget.present" -> widget.nonEmpty.toString,
      "syntaxWidget.text" -> presentation.fold("<absent>")(_.getText),
      "syntaxWidget.tooltip" -> presentation.fold("<absent>")(_.getTooltipText),
      "syntaxWidget.componentVisible" -> component.exists(_.isVisible).toString,
      "syntaxWidget.componentShowing" -> component.exists(_.isShowing).toString
    )
  }

  private def onEdt[A](body: => A): A = {
    val application = ApplicationManager.getApplication
    if (application.isDispatchThread) body
    else {
      val result = new AtomicReference[A]()
      application.invokeAndWait(new Runnable {
        override def run(): Unit = result.set(body)
      })
      result.get()
    }
  }

  private def hasLoadedClass(loader: ClassLoader, name: String): Boolean =
    loader.getClass.getMethods
      .find(method => method.getName == "hasLoadedClass" && method.getParameterCount == 1)
      .getOrElse(error("Plugin classloader does not expose loaded-class state"))
      .invoke(loader, name)
      .asInstanceOf[java.lang.Boolean]
      .booleanValue()

  private def invoke(owner: Class[_], receiver: AnyRef, name: String): AnyRef = {
    val method: Method = owner.getMethod(name)
    method.invoke(receiver)
  }
}

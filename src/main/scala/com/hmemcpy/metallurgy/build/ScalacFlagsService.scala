package com.hmemcpy.metallurgy.build

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleManager}
import com.intellij.openapi.project.Project
import com.intellij.util.Alarm
import org.jetbrains.plugins.scala.settings.CompilerHighlightingListener

import scala.util.control.NonFatal

/** Keeps the bundled Scala compiler profile aligned with the active Scala 3 compiler backend. */
final class ScalacFlagsService(project: Project) extends Disposable:

  private val log            = Logger.getInstance(classOf[ScalacFlagsService])
  private val reconcileAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  subscribeToCompilerHighlighting()

  def enableFor(module: Module): Unit =
    if ModuleDetectionService.get(project).isActive(module) then
      val optional = Option.when(MetallurgySettings(project).isXsemanticdbEnabled)(
        ScalacFlagsService.SemanticDbFlag
      )
      update(module, ScalacFlagsService.RequiredFlags ++ optional)
    else
      disableFor(module)
      log.info(s"BETASTy flags not applied: the Scala 3 compiler backend is inactive for ${module.getName}")

  def disableFor(module: Module): Unit =
    update(module, Seq.empty)

  private[metallurgy] def additionalOptions(module: Module): Seq[String] =
    BundledCompilerSettingsBridge.additionalOptions(module)

  private[metallurgy] def compilerOptions(module: Module): Seq[String] =
    BundledCompilerSettingsBridge.compilerOptions(module)

  private[metallurgy] def presentationCompilerOptions(module: Module): Seq[String] =
    compilerOptions(module).filterNot(_ == ScalacFlagsService.BestEffortProducerFlag)

  private def subscribeToCompilerHighlighting(): Unit =
    ApplicationManager.getApplication.getMessageBus
      .connect(this)
      .subscribe(
        CompilerHighlightingListener.Topic,
        new CompilerHighlightingListener:
          override def compilerHighlightingScala2Changed(enabled: Boolean): Unit = ()
          override def compilerHighlightingScala3Changed(enabled: Boolean): Unit = scheduleReconciliation()
      )

  private def scheduleReconciliation(): Unit =
    reconcileAlarm.cancelAllRequests()
    reconcileAlarm.addRequest(() => reconcileAll(), 0)

  private def reconcileAll(): Unit =
    if !project.isDisposed then
      val detection = ModuleDetectionService.get(project)
      ModuleManager
        .getInstance(project)
        .getModules
        .foreach: module =>
          if detection.isActive(module) then enableFor(module)
          else
            Option(project.getServiceIfCreated(classOf[PcSessionManager])).foreach(_.discard(module))
            disableFor(module)

  private def update(module: Module, desiredManagedFlags: Seq[String]): Unit =
    try
      val current = BundledCompilerSettingsBridge.additionalOptions(module)
      val updated = current.filterNot(ScalacFlagsService.ManagedFlags.contains) ++ desiredManagedFlags
      if updated != current then BundledCompilerSettingsBridge.setAdditionalOptions(module, updated.distinct)
    catch
      case NonFatal(error) =>
        log.warn(s"Could not update Scala compiler flags for ${module.getName}", error)

  override def dispose(): Unit = ()

object ScalacFlagsService:
  val BestEffortProducerFlag: String = "-Ybest-effort"
  val BestEffortConsumerFlag: String = "-Ywith-best-effort-tasty"
  val RequiredFlags: Seq[String]     = Seq(BestEffortProducerFlag, BestEffortConsumerFlag)
  val SemanticDbFlag: String         = "-Xsemanticdb"
  val ManagedFlags: Set[String]      = (RequiredFlags :+ SemanticDbFlag).toSet

  def get(project: Project): ScalacFlagsService =
    project.getService(classOf[ScalacFlagsService])

/** The bundled plugin is built with Scala 2.13 while Metallurgy uses Scala 3. Its Java compiler-settings state is the
  * stable classloader boundary: Scala collections and case-class values never escape the bundled plugin.
  */
private object BundledCompilerSettingsBridge:

  private lazy val bundledClassLoader =
    Class.forName("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettingsProfile$").getClassLoader

  private lazy val profileModule =
    module("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettingsProfile$")

  private lazy val settingsModule =
    module("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettings$")

  def additionalOptions(moduleValue: Module): Seq[String] =
    val state = settingsState(profileFor(moduleValue))
    Option(state.getClass.getField("additionalCompilerOptions").get(state).asInstanceOf[Array[String]])
      .fold(Seq.empty)(_.toSeq)

  def compilerOptions(moduleValue: Module): Seq[String] =
    val settings = settingsFor(profileFor(moduleValue))
    val values   = settings.getClass
      .getMethod("getOptionsAsStrings", java.lang.Boolean.TYPE)
      .invoke(settings, Boolean.box(true))
    scalaStrings(values)

  def setAdditionalOptions(moduleValue: Module, options: Seq[String]): Unit =
    val profile = profileFor(moduleValue)
    val state   = settingsState(profile)
    state.getClass.getField("additionalCompilerOptions").set(state, options.toArray)
    val rebuilt = settingsModule.getClass
      .getMethod("fromState", state.getClass)
      .invoke(settingsModule, state)
    val _       = profile.getClass.getMethods
      .find(method => method.getName == "setSettings" && method.getParameterCount == 1)
      .getOrElse(throw new NoSuchMethodException("ScalaCompilerSettingsProfile.setSettings"))
      .invoke(profile, rebuilt)

  private def profileFor(moduleValue: Module): AnyRef =
    profileModule.getClass
      .getMethod("forModule", classOf[Module])
      .invoke(profileModule, moduleValue)
      .asInstanceOf[AnyRef]

  private def settingsState(profile: AnyRef): AnyRef =
    val settings = settingsFor(profile)
    settings.getClass.getMethod("toState").invoke(settings).asInstanceOf[AnyRef]

  private def settingsFor(profile: AnyRef): AnyRef =
    profile.getClass.getMethod("getSettings").invoke(profile).asInstanceOf[AnyRef]

  private def scalaStrings(values: AnyRef): Seq[String] =
    val iterator = values.getClass.getMethod("iterator").invoke(values)
    val hasNext  = iterator.getClass.getMethod("hasNext")
    val next     = iterator.getClass.getMethod("next")
    Iterator
      .continually(iterator)
      .takeWhile(value => hasNext.invoke(value).asInstanceOf[Boolean])
      .map(value => next.invoke(value).asInstanceOf[String])
      .toSeq

  private def module(className: String): AnyRef =
    Class
      .forName(className, true, bundledClassLoader)
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AnyRef]

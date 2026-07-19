package com.hmemcpy.metallurgy.build

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project

import scala.util.control.NonFatal

/** Keeps the bundled Scala compiler profile aligned with Metallurgy's per-module opt-in. */
final class ScalacFlagsService(project: Project):

  private val log = Logger.getInstance(classOf[ScalacFlagsService])

  def enableFor(module: Module): Unit =
    if ModuleDetectionService.get(project).isEligible(module) then
      val optional = Option.when(MetallurgySettings(project).isXsemanticdbEnabled)(
        ScalacFlagsService.SemanticDbFlag
      )
      update(module, ScalacFlagsService.RequiredFlags ++ optional)
    else log.info(s"BETASTy flags not applied: ${module.getName} is not a Scala 3.5+ module")

  def disableFor(module: Module): Unit =
    update(module, Seq.empty)

  private[metallurgy] def additionalOptions(module: Module): Seq[String] =
    BundledCompilerSettingsBridge.additionalOptions(module)

  private[metallurgy] def compilerOptions(module: Module): Seq[String] =
    BundledCompilerSettingsBridge.compilerOptions(module)

  private def update(module: Module, desiredManagedFlags: Seq[String]): Unit =
    try
      val current = BundledCompilerSettingsBridge.additionalOptions(module)
      val updated = current.filterNot(ScalacFlagsService.ManagedFlags.contains) ++ desiredManagedFlags
      if updated != current then BundledCompilerSettingsBridge.setAdditionalOptions(module, updated.distinct)
    catch
      case NonFatal(error) =>
        log.warn(s"Could not update Scala compiler flags for ${module.getName}", error)

object ScalacFlagsService:
  val RequiredFlags: Seq[String] = Seq("-Ybest-effort", "-Ywith-best-effort-tasty")
  val SemanticDbFlag: String     = "-Xsemanticdb"
  val ManagedFlags: Set[String]  = (RequiredFlags :+ SemanticDbFlag).toSet

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
    profile.getClass.getMethods
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

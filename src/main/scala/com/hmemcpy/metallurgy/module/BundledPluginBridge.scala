package com.hmemcpy.metallurgy.module

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement

import java.lang.reflect.{Field, Method}

/** Reflection-based bridge to the bundled Scala plugin's APIs. Avoids LinkageError from scala.Option class identity
  * mismatch between our Scala 3 classloader and the bundled Scala 2.13 classloader.
  */
object BundledPluginBridge:

  private lazy val bundledClassLoader: ClassLoader =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType").getClassLoader

  // --- ModuleExt.scalaMinorVersion ---

  private lazy val moduleExtClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.project.package$ModuleExt", true, bundledClassLoader)

  private lazy val moduleExtCtor = moduleExtClass.getConstructors.head

  private lazy val scalaMinorVersionMethod: Method =
    moduleExtClass.getMethod("scalaMinorVersion")

  private lazy val optionIsEmptyMethod: Method =
    Class.forName("scala.Option", true, bundledClassLoader).getMethod("isEmpty")

  private lazy val optionGetMethod: Method =
    Class.forName("scala.Option", true, bundledClassLoader).getMethod("get")

  def optionValue(option: AnyRef): Option[AnyRef] =
    if option == null || optionIsEmptyMethod.invoke(option).asInstanceOf[Boolean] then None
    else Some(optionGetMethod.invoke(option).asInstanceOf[AnyRef])

  private lazy val scalaVersionMinorMethod: Method =
    Class.forName("org.jetbrains.plugins.scala.ScalaVersion", true, bundledClassLoader).getMethod("minor")

  def getScalaVersion(module: Module): String =
    try
      val ext          = moduleExtCtor.newInstance(module)
      val option       = scalaMinorVersionMethod.invoke(ext)
      if option == null then return null
      val isEmpty      = optionIsEmptyMethod.invoke(option).asInstanceOf[Boolean]
      if isEmpty then return null
      val scalaVersion = optionGetMethod.invoke(option)
      scalaVersionMinorMethod.invoke(scalaVersion).asInstanceOf[String]
    catch case _: Throwable => null

  // --- CompilerType ---

  private lazy val compilerTypeModuleClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$", true, bundledClassLoader)

  private lazy val compilerTypeModuleInstance: Any =
    compilerTypeModuleClass.getField("MODULE$").get(null)

  private lazy val typeKeyField: Field =
    val f = compilerTypeModuleClass.getDeclaredField("TypeKey")
    f.setAccessible(true)
    f

  private lazy val typeKey: Key[String] =
    typeKeyField.get(compilerTypeModuleInstance).asInstanceOf[Key[String]]

  def getCompilerType(element: PsiElement): String =
    element.getCopyableUserData(typeKey)

  def setCompilerType(element: PsiElement, value: String): Unit =
    element.putCopyableUserData(typeKey, value)

  def clearCompilerType(element: PsiElement): Unit =
    element.putCopyableUserData(typeKey, null)

  // --- CompilerType.Topic and Listener ---

  private lazy val topicField: Field =
    val f = compilerTypeModuleClass.getDeclaredField("Topic")
    f.setAccessible(true)
    f

  lazy val compilerTypeTopic: com.intellij.util.messages.Topic[?] =
    topicField.get(compilerTypeModuleInstance).asInstanceOf[com.intellij.util.messages.Topic[?]]

  lazy val listenerClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$Listener", true, bundledClassLoader)

  // --- ScalaProjectSettings ---

  private lazy val scalaProjectSettingsClass =
    Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings", true, bundledClassLoader)

  private lazy val scalaProjectSettingsInstance =
    scalaProjectSettingsClass.getMethod("getInstance", classOf[Project])

  def usesCompilerTypes(project: Project): Boolean =
    val settings = scalaProjectSettingsInstance.invoke(null, project)
    settings.getClass.getMethod("isCompilerHighlightingScala3").invoke(settings).asInstanceOf[Boolean] &&
    settings.getClass.getMethod("isUseCompilerTypes").invoke(settings).asInstanceOf[Boolean]

  // --- Compiler events ---

  private lazy val compilerEventListenerModuleClass =
    Class.forName("org.jetbrains.plugins.scala.compiler.CompilerEventListener$", true, bundledClassLoader)

  private lazy val compilerEventListenerModule =
    compilerEventListenerModuleClass.getField("MODULE$").get(null)

  lazy val compilerEventTopic: com.intellij.util.messages.Topic[?] =
    compilerEventListenerModuleClass
      .getMethod("topic")
      .invoke(compilerEventListenerModule)
      .asInstanceOf[com.intellij.util.messages.Topic[?]]

  lazy val compilerEventListenerClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.compiler.CompilerEventListener", true, bundledClassLoader)

  def clearScalaTypeCaches(project: Project, element: PsiElement): Unit =
    val managerModuleClass =
      Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager$", true, bundledClassLoader)
    val managerModule      = managerModuleClass.getField("MODULE$").get(null)
    val manager            = managerModuleClass.getMethod("instance", classOf[Project]).invoke(managerModule, project)
    manager.getClass.getMethod("clearOnScalaElementChange", classOf[PsiElement]).invoke(manager, element)

    val modTrackerClass = Class.forName("org.jetbrains.plugins.scala.caches.ModTracker$", true, bundledClassLoader)
    val modTracker      = modTrackerClass.getField("MODULE$").get(null)
    val tracker         = modTrackerClass.getMethod("anyScalaPsiChange").invoke(modTracker)
    tracker.getClass.getMethod("incModificationCount").invoke(tracker)

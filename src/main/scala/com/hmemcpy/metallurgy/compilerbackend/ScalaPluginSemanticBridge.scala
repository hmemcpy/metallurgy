package com.hmemcpy.metallurgy.compilerbackend

import com.intellij.openapi.module.Module
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.file.Path

/** IntelliJ-side compatibility seam for bundled Scala-plugin semantics.
  *
  * Public settings and PSI interfaces are used directly. Structural/reflected access is confined here where the bundled
  * plugin exposes no sufficient extension point or cross-classloader-safe interface.
  */
object ScalaPluginSemanticBridge:

  def install(): CompilerBackendShimStatus =
    BundledCompilerBackendShim.install()

  private lazy val bundledClassLoader: ClassLoader =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType").getClassLoader

  private lazy val compilerSettingsProfileModule =
    scalaModule("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettingsProfile$")

  private lazy val compilerSettingsModule =
    scalaModule("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettings$")

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

  // --- Compiler settings ---

  def additionalCompilerOptions(module: Module): Seq[String] =
    val state = compilerSettingsState(compilerProfileFor(module))
    Option(state.getClass.getField("additionalCompilerOptions").get(state).asInstanceOf[Array[String]])
      .fold(Seq.empty)(_.toSeq)

  def compilerOptions(module: Module): Seq[String] =
    val settings = compilerSettingsFor(compilerProfileFor(module))
    val values   = settings.getClass
      .getMethod("getOptionsAsStrings", java.lang.Boolean.TYPE)
      .invoke(settings, Boolean.box(true))
    scalaStrings(values)

  def setAdditionalCompilerOptions(module: Module, options: Seq[String]): Unit =
    val profile = compilerProfileFor(module)
    val state   = compilerSettingsState(profile)
    state.getClass.getField("additionalCompilerOptions").set(state, options.toArray)
    val rebuilt = compilerSettingsModule.getClass
      .getMethod("fromState", state.getClass)
      .invoke(compilerSettingsModule, state)
    val _       = profile.getClass.getMethods
      .find(method => method.getName == "setSettings" && method.getParameterCount == 1)
      .getOrElse(throw new NoSuchMethodException("ScalaCompilerSettingsProfile.setSettings"))
      .invoke(profile, rebuilt)

  private def compilerProfileFor(module: Module): AnyRef =
    compilerSettingsProfileModule.getClass
      .getMethod("forModule", classOf[Module])
      .invoke(compilerSettingsProfileModule, module)
      .asInstanceOf[AnyRef]

  private def compilerSettingsState(profile: AnyRef): AnyRef =
    val settings = compilerSettingsFor(profile)
    settings.getClass.getMethod("toState").invoke(settings).asInstanceOf[AnyRef]

  private def compilerSettingsFor(profile: AnyRef): AnyRef =
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

  private def scalaModule(className: String): AnyRef =
    Class
      .forName(className, true, bundledClassLoader)
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AnyRef]

  // --- CompilerType ---

  private lazy val compilerTypeModuleClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$", true, bundledClassLoader)

  private lazy val compilerTypeModuleInstance: AnyRef =
    compilerTypeModuleClass.getField("MODULE$").get(null).asInstanceOf[AnyRef]

  private lazy val compilerTypeApply: Method =
    compilerTypeModuleClass.getMethod("apply", classOf[PsiElement])

  private lazy val scalaOptionModule: AnyRef =
    scalaModule("scala.Option$")

  private lazy val scalaOptionApply: Method =
    scalaOptionModule.getClass.getMethod("apply", classOf[Object])

  private lazy val compilerTypeUpdate: Method =
    compilerTypeModuleClass.getMethod(
      "update",
      classOf[PsiElement],
      Class.forName("scala.Option", true, bundledClassLoader)
    )

  def getCompilerType(element: PsiElement): String =
    optionValue(compilerTypeApply.invoke(compilerTypeModuleInstance, element).asInstanceOf[AnyRef])
      .fold(null)(_.asInstanceOf[String])

  def setCompilerType(element: PsiElement, value: String): Unit =
    val compilerType = scalaOptionApply.invoke(scalaOptionModule, value).asInstanceOf[AnyRef]
    val _            = compilerTypeUpdate.invoke(compilerTypeModuleInstance, element, compilerType)

  def clearCompilerType(element: PsiElement): Unit =
    setCompilerType(element, null)

  // --- CompilerType.Topic and Listener ---

  private lazy val compilerTypeTopic: com.intellij.util.messages.Topic[?] =
    compilerTypeModuleClass
      .getMethod("Topic")
      .invoke(compilerTypeModuleInstance)
      .asInstanceOf[com.intellij.util.messages.Topic[?]]

  private lazy val listenerClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$Listener", true, bundledClassLoader)

  def subscribeToCompilerTypeRequests(project: Project, owner: Disposable)(callback: PsiElement => Unit): Unit =
    val handler  = new InvocationHandler:
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
        if method.getName == "onCompilerTypeRequest" && args != null && args.nonEmpty then
          callback(args(0).asInstanceOf[PsiElement])
        null
    val listener = Proxy.newProxyInstance(listenerClass.getClassLoader, Array(listenerClass), handler)
    project.getMessageBus
      .connect(owner)
      .subscribe(
        compilerTypeTopic.asInstanceOf[com.intellij.util.messages.Topic[AnyRef]],
        listener.asInstanceOf[AnyRef]
      )

  // --- ScalaProjectSettings ---

  def usesCompilerTypes(project: Project): Boolean =
    val settings = ScalaProjectSettings.getInstance(project)
    settings.isCompilerHighlightingScala3 && settings.isUseCompilerTypes

  // --- Compiler events ---

  private lazy val compilerEventListenerModuleClass =
    Class.forName("org.jetbrains.plugins.scala.compiler.CompilerEventListener$", true, bundledClassLoader)

  private lazy val compilerEventListenerModule =
    compilerEventListenerModuleClass.getField("MODULE$").get(null)

  private lazy val compilerEventTopic: com.intellij.util.messages.Topic[?] =
    compilerEventListenerModuleClass
      .getMethod("topic")
      .invoke(compilerEventListenerModule)
      .asInstanceOf[com.intellij.util.messages.Topic[?]]

  private lazy val compilerEventListenerClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.compiler.CompilerEventListener", true, bundledClassLoader)

  def subscribeToCompilerMessages(project: Project, owner: Disposable)(callback: ScalaCompilerMessage => Unit): Unit =
    val handler  = new InvocationHandler:
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
        if method.getName == "eventReceived" && args != null && args.nonEmpty then
          compilerMessage(args(0)).foreach(callback)
        null
    val listener = Proxy.newProxyInstance(
      compilerEventListenerClass.getClassLoader,
      Array(compilerEventListenerClass),
      handler
    )
    project.getMessageBus
      .connect(owner)
      .subscribe(
        compilerEventTopic.asInstanceOf[com.intellij.util.messages.Topic[AnyRef]],
        listener.asInstanceOf[AnyRef]
      )

  private def compilerMessage(event: AnyRef): Option[ScalaCompilerMessage] =
    Option
      .when(event.getClass.getSimpleName == "MessageEmitted"):
        val message = event.getClass.getMethod("msg").invoke(event).asInstanceOf[AnyRef]
        for
          source <- reflectedOption(message, "source")
          begin  <- reflectedOption(message, "problemStart")
          end    <- reflectedOption(message, "problemEnd")
        yield ScalaCompilerMessage(
          source.getClass.getMethod("toPath").invoke(source).asInstanceOf[Path],
          message.getClass.getMethod("text").invoke(message).asInstanceOf[String],
          positionLine(begin),
          positionColumn(begin),
          positionLine(end),
          positionColumn(end)
        )
      .flatten

  private def reflectedOption(owner: AnyRef, methodName: String): Option[AnyRef] =
    optionValue(owner.getClass.getMethod(methodName).invoke(owner).asInstanceOf[AnyRef])

  private def positionLine(position: AnyRef): Int =
    position.getClass.getMethod("line").invoke(position).asInstanceOf[Int]

  private def positionColumn(position: AnyRef): Int =
    position.getClass.getMethod("column").invoke(position).asInstanceOf[Int]

  def clearScalaTypeCacheForElement(project: Project, element: PsiElement): Unit =
    val managerModuleClass =
      Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager$", true, bundledClassLoader)
    val managerModule      = managerModuleClass.getField("MODULE$").get(null)
    val manager            = managerModuleClass.getMethod("instance", classOf[Project]).invoke(managerModule, project)
    val _                  = manager.getClass.getMethod("clearOnScalaElementChange", classOf[PsiElement]).invoke(manager, element)

  def clearScalaTypeCaches(project: Project, element: PsiElement): Unit =
    clearScalaTypeCacheForElement(project, element)
    bumpAnyScalaPsiChange()

  /** Invalidate all Scala type caches project-wide. */
  def invalidateScalaTypeCaches(): Unit =
    bumpAnyScalaPsiChange()

  private[metallurgy] def scalaPsiModificationCount: Long =
    anyScalaPsiChangeTracker.getClass
      .getMethod("getModificationCount")
      .invoke(anyScalaPsiChangeTracker)
      .asInstanceOf[Long]

  private def bumpAnyScalaPsiChange(): Unit =
    val _ = anyScalaPsiChangeTracker.getClass.getMethod("incModificationCount").invoke(anyScalaPsiChangeTracker)

  private def anyScalaPsiChangeTracker: AnyRef =
    val modTrackerClass = Class.forName("org.jetbrains.plugins.scala.caches.ModTracker$", true, bundledClassLoader)
    val modTracker      = modTrackerClass.getField("MODULE$").get(null)
    modTrackerClass.getMethod("anyScalaPsiChange").invoke(modTracker)

private[metallurgy] final case class ScalaCompilerMessage(
    path: Path,
    text: String,
    beginLine: Int,
    beginColumn: Int,
    endLine: Int,
    endColumn: Int
)

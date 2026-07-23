package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.openapi.module.Module
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiNamedElement}
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import scala.util.control.NonFatal

/** IntelliJ-side compatibility seam for bundled Scala-plugin semantics.
  *
  * Public settings and PSI interfaces are used directly. Structural/reflected access is confined here where the bundled
  * plugin exposes no sufficient extension point or cross-classloader-safe interface.
  */
object ScalaPluginSemanticBridge:

  def install(): CompilerBackendShimStatus =
    BundledCompilerBackendShim.install()

  /** Preserves every non-empty bundled result and supplies a compiler symbol only when bundled resolution found
    * nothing. This method is the sole Scala-plugin-private construction point used by the resolver wrapper.
    */
  def referenceResolution(reference: Object, bundledResult: Object): Object =
    bundledResult match
      case results: Array[?] if results.nonEmpty => bundledResult
      case _: Array[?]                           =>
        try
          reference match
            case element: PsiElement =>
              val module = ModuleUtilCore.findModuleForPsiElement(element)
              if module == null || !ModuleDetectionService.get(element.getProject).isActive(module) then bundledResult
              else
                Scala3CompilerBackend
                  .get(element.getProject)
                  .symbolTargetFor(element, module, CompilerBackendRole.Reference)
                  .collect:
                    case named: PsiNamedElement => Array(new ScalaResolveResult(named)).asInstanceOf[Object]
                  .getOrElse(bundledResult)
            case _                   => bundledResult
        catch
          case control: ControlFlowException => throw control
          case NonFatal(_)                   => bundledResult
      case _                                     => bundledResult

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

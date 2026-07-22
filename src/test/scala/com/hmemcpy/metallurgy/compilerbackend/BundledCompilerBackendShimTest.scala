package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.hmemcpy.metallurgy.pc.{PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScPattern}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunction,
  ScFunctionDefinition,
  ScValueOrVariableDefinition
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScGivenDefinition
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}
import org.jetbrains.org.objectweb.asm.{ClassWriter, Opcodes}

import java.lang.management.ManagementFactory
import java.util.Arrays
import java.util.concurrent.TimeUnit

final class BundledCompilerBackendShimTest extends ScalaLightCodeInsightFixtureTestCase:

  private final case class FastPathMeasurement(bytesPerCall: Double, p50: Long, p95: Long, max: Long, result: Object)

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)
    assertTrue(ScalaPluginSemanticBridge.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testPublishedCompilerTypeReplacesDeclaredType(): Unit =
    val file        = myFixture.configureByText("DeclaredType.scala", "val value: String = \"text\"")
    val typeElement = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    val version     = myFixture.getEditor.getDocument.getModificationStamp

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend.get(getProject).publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    )
    assertEquals("Int", rendered(typeElement))

  def testRealPresentationCompilerResultFlowsThroughDeclaredTypeBackend(): Unit =
    val source       = "val value: String = \"text\""
    val file         = myFixture.configureByText("PcDeclaredType.scala", source)
    val typeElement  = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    val document     = myFixture.getEditor.getDocument
    val session      = PlatformTestUtil
      .waitForFuture(PcSessionManager.get(getProject).prepareFile(file.getVirtualFile), 60000L)
      .getOrElse(throw new AssertionError("presentation compiler session was unavailable"))
    val snapshot     = PcSnapshot(file.getVirtualFile.getUrl, document.getModificationStamp, document.getText)
    val typeRange    = typeElement.getTextRange
    val compilerType = ApplicationManager.getApplication
      .executeOnPooledThread(() => TypeRenderer.render(session, snapshot, typeRange))
      .get(30, TimeUnit.SECONDS)
      .getOrElse(throw new AssertionError("presentation compiler returned no declared type"))
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(typeElement, CompilerBackendRole.DeclaredType, snapshot.documentVersion, compilerType)
    )
    val selectedType = typeElement.`type`().fold(failure => throw new AssertionError(failure.toString), identity)
    Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(typeElement, getModule, CompilerBackendRole.DeclaredType) match
      case CompilerBackendState.Current(renderedType, result) =>
        assertEquals(compilerType, renderedType)
        assertSame(selectedType, result.fold(failure => throw new AssertionError(failure.toString), identity))
      case state                                              => throw new AssertionError(s"expected current compiler result, got $state")

  def testPublishedDefinitionTypeReplacesBundledDeclaredType(): Unit =
    val file       = myFixture.configureByText("DefinitionType.scala", "val value: String = \"text\"")
    val definition = PsiTreeUtil.findChildOfType(file, classOf[ScValueOrVariableDefinition])

    publish(definition, CompilerBackendRole.Definition, "Int")

    assertEquals("Int", rendered(definition.`type`()))

  def testPublishedFunctionResultFlowsThroughScalaAndJavaPsi(): Unit =
    val file     = myFixture.configureByText("FunctionResult.scala", "def function: String = \"text\"")
    val function = PsiTreeUtil.findChildOfType(file, classOf[ScFunction])

    publish(function, CompilerBackendRole.FunctionResult, "Int")

    assertEquals("Int", rendered(function.returnType))
    assertEquals("int", function.getReturnType.getCanonicalText)

  def testPublishedParameterTypePreservesParameterViewsAndJavaPsi(): Unit =
    val file      = myFixture.configureByText("ParameterType.scala", "def function(value: String): Unit = ()")
    val parameter = PsiTreeUtil.findChildOfType(file, classOf[ScParameter])

    publish(parameter, CompilerBackendRole.Parameter, "Int")

    assertEquals("Int", rendered(parameter.`type`()))
    assertEquals("Int", rendered(parameter.insideParamType))
    assertEquals("Int", rendered(parameter.outsideParamType))
    assertEquals("int", parameter.getType.getCanonicalText)

  def testPublishedPatternAndExpectedTypesReplaceBundledResults(): Unit =
    val file    = myFixture.configureByText("PatternType.scala", "val (value, _) = (1, \"text\")")
    val pattern = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .stream()
      .filter(_.getText == "value")
      .findFirst()
      .orElseThrow()

    publish(pattern, CompilerBackendRole.Pattern, "String")
    publish(pattern, CompilerBackendRole.PatternExpected, "Boolean")

    assertEquals("_root_.scala.Predef.String", rendered(pattern.`type`()))
    assertEquals("Boolean", pattern.expectedType.map(_.canonicalText).orNull)

  def testRealSnapshotDrivesFunctionsParametersAndDestructuredBindings(): Unit =
    val source =
      """object Main:
        |  def literal = 1
        |  def polymorphic[A](value: A): A = value
        |  def parameters(byName: => String, repeated: Int*): Unit = ()
        |  var mutable = "three"
        |  val (number, text) = (1, "two")
        |""".stripMargin
    val file   = myFixture.configureByText("SemanticRoots.scala", source)

    val _ = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    val functions   = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunction]).stream().toList
    val parameters  = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameter]).stream().toList
    val bindings    = PsiTreeUtil.findChildrenOfType(file, classOf[ScBindingPattern]).stream().toList
    val definitions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
      .stream()
      .toList

    val literal     = functions.stream().filter(_.name == "literal").findFirst().orElseThrow()
    val polymorphic = functions.stream().filter(_.name == "polymorphic").findFirst().orElseThrow()
    assertEquals("Int", rendered(literal.returnType))
    assertEquals("A", rendered(polymorphic.returnType))
    Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(polymorphic, getModule, CompilerBackendRole.Function) match
      case CompilerBackendState.Rendered(renderedType) =>
        assertEquals("(Main.polymorphic : [A](value: A): A)", renderedType)
      case state                                       => throw new AssertionError(s"expected rendered polymorphic method type, got $state")

    val byName   = parameters.stream().filter(_.name == "byName").findFirst().orElseThrow()
    val repeated = parameters.stream().filter(_.name == "repeated").findFirst().orElseThrow()
    assertEquals("_root_.scala.Predef.String", rendered(byName.`type`()))
    assertEquals("_root_.scala.Predef.String", rendered(byName.insideParamType))
    assertEquals("Int", rendered(repeated.`type`()))
    assertEquals("scala.Seq[Int]", rendered(repeated.outsideParamType))

    val number  = bindings.stream().filter(_.name == "number").findFirst().orElseThrow()
    val text    = bindings.stream().filter(_.name == "text").findFirst().orElseThrow()
    val mutable = definitions
      .stream()
      .filter(_.bindings.exists(_.name == "mutable"))
      .findFirst()
      .orElseThrow()
    assertEquals("Int", rendered(number.`type`()))
    assertEquals("_root_.scala.Predef.String", rendered(text.`type`()))
    assertEquals("Int", number.expectedType.map(_.canonicalText).orNull)
    assertEquals("_root_.scala.Predef.String", text.expectedType.map(_.canonicalText).orNull)
    assertEquals("_root_.scala.Predef.String", rendered(mutable.`type`()))
    assertEquals("_root_.scala.Predef.String", rendered(mutable.bindings.head.`type`()))

  def testRealSnapshotPreservesSingletonOverrideAndGivenResults(): Unit =
    val source =
      """trait Base:
        |  def overridden: Any
        |object Main extends Base:
        |  override def overridden = "text"
        |  val singleton: "literal" = "literal"
        |  given ordering: Ordering[Int] = Ordering.Int
        |  given structural: Ordering[String] with
        |    def compare(left: String, right: String): Int = left.compareTo(right)
        |""".stripMargin
    val file   = myFixture.configureByText("ExactSemanticRoots.scala", source)

    val _ = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    val functions  = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunction]).stream().toList
    val overridden = functions
      .stream()
      .filter(function => function.name == "overridden" && function.isInstanceOf[ScFunctionDefinition])
      .findFirst()
      .orElseThrow()
    val givenAlias = functions.stream().filter(_.name == "ordering").findFirst().orElseThrow()
    val singleton  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeElement])
      .stream()
      .filter(_.getText == "\"literal\"")
      .findFirst()
      .orElseThrow()
    val structural = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScGivenDefinition])
      .stream()
      .filter(_.name == "structural")
      .findFirst()
      .orElseThrow()

    Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(overridden, getModule, CompilerBackendRole.FunctionResult) match
      case CompilerBackendState.Current(renderedType, _) => assertEquals("Any", renderedType)
      case state                                         => throw new AssertionError(s"expected current compiler result, got $state")
    assertEquals("Any", rendered(overridden.returnType))
    assertEquals("\"literal\"", rendered(singleton.`type`()))
    assertEquals("scala.Ordering[Int]", rendered(givenAlias.returnType))
    assertEquals("scala.Ordering[_root_.scala.Predef.String]", rendered(structural.givenType()))

  def testPendingBackendFallsThroughToBundledType(): Unit =
    assertStateFallsThrough(CompilerBackendState.Pending)

  def testUnavailableBackendFallsThroughToBundledType(): Unit =
    assertStateFallsThrough(CompilerBackendState.Unavailable)

  def testFailedBackendFallsThroughToBundledType(): Unit =
    assertStateFallsThrough(CompilerBackendState.Failed)

  def testStaleDocumentVersionFallsThroughToBundledType(): Unit =
    val (typeElement, version) = declaredString("StaleVersion.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version - 1L, "Int")

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testNonOptedInModuleFallsThroughToBundledType(): Unit =
    val (typeElement, version) = declaredString("NotOptedIn.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testInactiveModuleFallsThroughAtEverySemanticRoot(): Unit =
    val source =
      """object Main:
        |  val definition: String = "text"
        |  def function: String = "text"
        |  def owner(parameter: String): Unit = ()
        |  val (pattern, _) = (1, "text")
        |""".stripMargin
    val file   = myFixture.configureByText("InactiveSemanticRoots.scala", source)

    val definition = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
      .stream()
      .filter(_.bindings.exists(_.name == "definition"))
      .findFirst()
      .orElseThrow()
    val function   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .stream()
      .filter(_.name == "function")
      .findFirst()
      .orElseThrow()
    val parameter  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .stream()
      .filter(_.name == "parameter")
      .findFirst()
      .orElseThrow()
    val pattern    = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .stream()
      .filter(_.name == "pattern")
      .findFirst()
      .orElseThrow()

    publish(definition, CompilerBackendRole.Definition, "Int")
    publish(function, CompilerBackendRole.FunctionResult, "Int")
    publish(parameter, CompilerBackendRole.Parameter, "Int")
    publish(pattern, CompilerBackendRole.Pattern, "String")
    assertEquals("Int", rendered(definition.`type`()))
    assertEquals("Int", rendered(function.returnType))
    assertEquals("Int", rendered(parameter.`type`()))
    assertEquals("_root_.scala.Predef.String", rendered(pattern.`type`()))

    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals("_root_.scala.Predef.String", rendered(definition.`type`()))
    assertEquals("_root_.scala.Predef.String", rendered(function.returnType))
    assertEquals("_root_.scala.Predef.String", rendered(parameter.`type`()))
    assertEquals("Int", rendered(pattern.`type`()))

  def testInactiveModuleRejectsPublication(): Unit =
    val (typeElement, version) = declaredString("RejectedPublication.scala")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals(
      CompilerBackendPublication.IgnoredInactive,
      Scala3CompilerBackend.get(getProject).publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    )
    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testActiveCompilerTypeReadRejectsCopiedSlotWithoutCurrentSideTableEntry(): Unit =
    val file       = myFixture.configureByText("RejectedCopiedSlot.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    BundledPluginBridge.setCompilerType(expression, "String")
    val copied     = expression.copy().asInstanceOf[ScExpression]

    assertTrue(compilerType(copied).isEmpty)
    assertTrue(Option(BundledPluginBridge.getCompilerType(copied)).isEmpty)
    assertTrue(compilerType(expression).isEmpty)
    assertTrue(Option(BundledPluginBridge.getCompilerType(expression)).isEmpty)

  def testInactiveCompilerTypeReadPreservesBundledSlot(): Unit =
    val file       = myFixture.configureByText("InactiveCopiedSlot.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    BundledPluginBridge.setCompilerType(expression, "String")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals(Some("String"), compilerType(expression))
    assertEquals("String", BundledPluginBridge.getCompilerType(expression))

  def testCurrentExpressionRepairsLateBundledCompilerTypeWrite(): Unit =
    val file       = myFixture.configureByText("LateCompilerType.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    val version    = myFixture.getEditor.getDocument.getModificationStamp
    val backend    = Scala3CompilerBackend.get(getProject)
    assertEquals(
      CompilerBackendPublication.Published,
      backend.publish(expression, CompilerBackendRole.ExpressionExact, version, "String")
    )
    BundledPluginBridge.setCompilerType(expression, "Boolean")

    assertEquals(Some("String"), compilerType(expression))
    assertEquals("String", BundledPluginBridge.getCompilerType(expression))

  def testLocalOptOutKeepsPublishedTypeWhenGlobalOptInRemainsActive(): Unit =
    val settings               = MetallurgySettings(getProject)
    val (typeElement, version) = declaredString("GlobalOptIn.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    settings.setGloballyEnabled(enabled = true)
    settings.setEnabled(getModule, enabled = false)

    try assertEquals("Int", rendered(typeElement))
    finally settings.setGloballyEnabled(enabled = false)

  def testCompilerHighlightingOffFallsThroughToBundledType(): Unit =
    val (typeElement, version) = declaredString("CompilerHighlightingOff.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    setCompilerBasedHighlighting(enabled = false)

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testDiscoveryCoversInstalledSemanticRoots(): Unit =
    val discovery = CompilerBackendShimDiscovery
      .discover(classOf[ScTypeElement])
      .fold(reason => throw new AssertionError(reason), identity)
    val roles     = discovery.semanticTargets.flatMap(_.methods.map(_.role)).toSet

    assertTrue(discovery.unavailableRoots.mkString(", "), discovery.unavailableRoots.isEmpty)
    assertTrue(discovery.compilerTypeTarget.nonEmpty)
    assertTrue(discovery.patternImplementations.size >= 17)
    discovery.patternImplementations.foreach: pattern =>
      assertTrue(pattern.className, pattern.hookClassName.nonEmpty)
    assertTrue(
      CompilerBackendRole.values.forall(role =>
        roles.contains(role) || role == CompilerBackendRole.Binding ||
          role == CompilerBackendRole.ExpressionExact || role == CompilerBackendRole.ExpressionWidened ||
          role == CompilerBackendRole.Function
      )
    )

  def testStructurallyIncompatiblePluginCannotInstall(): Unit =
    val discovery = CompilerBackendShimDiscovery.discoverClassBytes(Vector.empty)

    assertFalse(discovery.canInstall)
    assertTrue(discovery.unavailableRoots.nonEmpty)

  def testPartiallyCompatiblePluginCannotInstall(): Unit =
    val writer = new ClassWriter(0)
    writer.visit(
      Opcodes.V17,
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
      "org/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement",
      null,
      "java/lang/Object",
      Array.empty
    )
    val method = writer.visitMethod(Opcodes.ACC_PUBLIC, "type", "()Lscala/util/Either;", null, null)
    method.visitCode()
    method.visitInsn(Opcodes.ACONST_NULL)
    method.visitInsn(Opcodes.ARETURN)
    method.visitMaxs(1, 1)
    method.visitEnd()
    writer.visitEnd()

    val discovery = CompilerBackendShimDiscovery.discoverClassBytes(Vector(writer.toByteArray))

    assertTrue(discovery.semanticTargets.nonEmpty)
    assertTrue(discovery.unavailableRoots.nonEmpty)
    assertFalse(discovery.canInstall)

  def testInactiveBackendSelectorAllocationAndLatency(): Unit =
    val (typeElement, _) = declaredString("FastPath.scala")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    val bridge           = Class.forName(
      "org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge",
      false,
      classOf[ScTypeElement].getClassLoader
    )
    val iterations       = 20000
    val baseline         =
      try
        val _ = bridge.getMethod("disable").invoke(null)
        measureFastPath(iterations)(typeElement.`type`().asInstanceOf[Object])
      finally
        val _ = bridge.getMethod("enable").invoke(null)
    val hooked           = measureFastPath(iterations)(typeElement.`type`().asInstanceOf[Object])
    val byteCost         = math.max(0.0, hooked.bytesPerCall - baseline.bytesPerCall)
    println(
      f"[compiler-backend-fast-path] calls=$iterations " +
        f"baseline=${baseline.bytesPerCall}%.2fB/${baseline.p50}ns/${baseline.p95}ns/${baseline.max}ns " +
        f"inactive=${hooked.bytesPerCall}%.2fB/${hooked.p50}ns/${hooked.p95}ns/${hooked.max}ns " +
        f"incremental=$byteCost%.2fB"
    )

    assertNotNull(baseline.result)
    assertNotNull(hooked.result)
    // Wall-clock latency is reported for the go/no-go record but is not a stable CI assertion on shared hosts.
    assertTrue(s"inactive installed path allocated $byteCost incremental bytes/call", byteCost <= 64.0)

  private def assertStateFallsThrough(state: CompilerBackendState): Unit =
    val (typeElement, version) = declaredString(s"${state.productPrefix}.scala")
    Scala3CompilerBackend
      .get(getProject)
      .publishState(typeElement, CompilerBackendRole.DeclaredType, version, state)

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  private def declaredString(fileName: String): (ScTypeElement, Long) =
    val file        = myFixture.configureByText(fileName, "val value: String = \"text\"")
    val typeElement = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    typeElement -> myFixture.getEditor.getDocument.getModificationStamp

  private def rendered(typeElement: ScTypeElement): String =
    typeElement.`type`().fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

  private def rendered(result: org.jetbrains.plugins.scala.lang.psi.types.result.TypeResult): String =
    result.fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

  private def publish(element: PsiElement, role: CompilerBackendRole, renderedType: String): Unit =
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(element, role, myFixture.getEditor.getDocument.getModificationStamp, renderedType)
    )

  private def compilerType(element: PsiElement): Option[String] =
    val moduleClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$")
    val module      = moduleClass.getField("MODULE$").get(null)
    val option      = moduleClass.getMethod("apply", classOf[PsiElement]).invoke(module, element).asInstanceOf[AnyRef]
    BundledPluginBridge.optionValue(option).map(_.toString)

  private def measureFastPath(iterations: Int)(operation: => Object): FastPathMeasurement =
    var warmup = 0
    while warmup < 5000 do
      val _ = operation
      warmup += 1

    val timings         = new Array[Long](iterations)
    val threadBean      = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val threadId        = Thread.currentThread().threadId()
    val allocatedBefore = threadBean.getThreadAllocatedBytes(threadId)
    var result: Object  = null
    var index           = 0
    while index < iterations do
      val started = System.nanoTime()
      result = operation
      timings(index) = System.nanoTime() - started
      index += 1
    val allocatedAfter  = threadBean.getThreadAllocatedBytes(threadId)
    Arrays.sort(timings)
    FastPathMeasurement(
      bytesPerCall = (allocatedAfter - allocatedBefore).toDouble / iterations,
      p50 = timings(iterations / 2),
      p95 = timings((iterations * 95) / 100),
      max = timings.last,
      result = result
    )

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)

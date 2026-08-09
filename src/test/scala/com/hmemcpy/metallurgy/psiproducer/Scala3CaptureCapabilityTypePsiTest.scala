package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.FileContentUtilCore
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScAnnotTypeElement, ScCaptureTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.cc.{ScCaptureFilter, ScCaptureRef, ScCaptureSet}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScFunctionalTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3CaptureCapabilityTypePsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  override protected def additionalCompilerOptions: Seq[String] =
    Seq("-language:experimental.captureChecking")

  def testCaptureTypesSetsReferencesAndModifiersUseNativePhysicalPsi(): Unit =
    val source =
      """class Capability extends caps.Capability
        |trait Holder:
        |  val cap: Capability
        |class Kind extends caps.Capability, caps.Classifier
        |class Box[A]
        |type Universal = Capability^
        |def captures(x: Capability, xs: List[Capability], h: Holder): Box[String]^{h.cap, x, xs*, x.rd, x.only[Kind]} = ???
        |def empty: Box[String]^{} = ???
        |""".stripMargin
    val file   = physical("CaptureTypes1.scala", source)

    val captureTypes = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScCaptureTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(
      Vector("Capability^", "Box[String]^{h.cap, x, xs*, x.rd, x.only[Kind]}", "Box[String]^{}"),
      captureTypes.map(_.getText)
    )
    assertEquals(Vector("Capability", "Box[String]", "Box[String]"), captureTypes.map(_.innerElement.getText))
    assertEquals(
      Vector(None, Some("{h.cap, x, xs*, x.rd, x.only[Kind]}"), Some("{}")),
      captureTypes.map(_.captureSet.map(_.getText))
    )
    captureTypes.foreach(value => assertSame(value, value.innerElement.getParent))

    val sets = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureSet]).asScala.toVector
    assertEquals(Vector("{h.cap, x, xs*, x.rd, x.only[Kind]}", "{}"), sets.map(_.getText))
    sets.foreach(value => assertTrue(value.getParent.isInstanceOf[ScCaptureTypeElement]))

    val references = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureRef]).asScala.toVector
    assertEquals(Vector("h.cap", "x", "xs*", "x.rd", "x.only[Kind]"), references.map(_.getText))
    assertEquals(Vector(false, false, true, false, false), references.map(_.hasCapabilityReach))
    assertEquals(Vector(false, false, false, true, false), references.map(_.isReadOnlyCapability))
    assertEquals(Vector("h.cap", "x", "xs", "x", "x"), references.flatMap(_.captureRef).map(_.getText))

    val filters = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureFilter]).asScala.toVector
    assertEquals(Vector(".only[Kind]"), filters.map(_.getText))
    assertEquals(Vector("Kind"), filters.map(_.filterId.getText))
    assertSame(filters.head, references.last.captureFilter.get)

    (captureTypes ++ sets ++ references ++ filters).foreach: element =>
      assertEquals(element.getText, element.getTextRange.substring(source))
      val children = element.getNode.getChildren(null).toVector
      assertFalse(element.getText, children.isEmpty)
      assertEquals(element.getText, children.map(_.getText).mkString)
      children.foreach(child => assertSame(element.getNode, child.getTreeParent))

  def testPureContextAndByNameCaptureSetsMountInExistingTypeRoles(): Unit =
    val source =
      """class Capability extends caps.Capability
        |trait Holder:
        |  val cap: Capability
        |class Kind extends caps.Capability, caps.Classifier
        |def pure(x: Capability): () ->{x} String = ???
        |def context(x: Capability, xs: List[Capability], h: Holder): Capability ?->{h.cap, xs*, x.rd, x.only[Kind]} String = ???
        |def byName(x: Capability)(value: ->{x} String): String = value
        |""".stripMargin
    val file   = physical("CaptureFunctions1.scala", source)

    val functions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionalTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(
      Vector("() ->{x} String", "Capability ?->{h.cap, xs*, x.rd, x.only[Kind]} String"),
      functions.map(_.getText)
    )
    assertEquals(Vector(false, true), functions.map(_.isContext))
    assertEquals(Vector(true, true), functions.map(_.isPure))
    assertEquals(Vector("String", "String"), functions.flatMap(_.returnTypeElement).map(_.getText))

    val byName = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .find(_.name == "value")
      .get
    assertTrue(byName.isCallByNameParameter)
    assertEquals(Some("String"), byName.typeElement.map(_.getText))

    val sets = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureSet]).asScala.toVector
    assertEquals(Vector("{x}", "{h.cap, xs*, x.rd, x.only[Kind]}", "{x}"), sets.map(_.getText))
    assertEquals(
      Vector("x", "h.cap", "xs*", "x.rd", "x.only[Kind]", "x"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureRef]).asScala.toVector.map(_.getText)
    )

  def testCaptureSetSpacingAndRecursiveTypeContextsRemainExact(): Unit =
    val source =
      """class Capability extends caps.Capability
        |class Box[A]
        |def shapes(x: Capability): (Box[String]^{x}, () -> {x} String, () ->{} String) = ???
        |""".stripMargin
    val file   = physical("CaptureShapes1.scala", source)

    val captureTypes = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureTypeElement]).asScala.toVector
    assertEquals(Vector("Box[String]^{x}"), captureTypes.map(_.getText))
    assertEquals("Box[String]", captureTypes.head.innerElement.getText)
    assertEquals(Some("{x}"), captureTypes.head.captureSet.map(_.getText))

    val functions = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunctionalTypeElement]).asScala.toVector
    assertEquals(
      Vector("() -> {x} String", "() ->{} String"),
      functions.map(_.getText)
    )
    assertTrue(functions.forall(_.isPure))
    val sets      = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureSet]).asScala.toVector
    assertEquals(Vector("{x}", "{x}", "{}"), sets.map(_.getText))
    (captureTypes ++ functions ++ sets).foreach(assertDirectChildren(_, source))

  def testPureByNameCaptureTypesMountInClassParameters(): Unit =
    val source =
      """class Capability extends caps.Capability
        |class Consumer(x: Capability, value: -> String, captured: ->{x} String)
        |""".stripMargin
    val file   = physical("CaptureClassParameters1.scala", source)

    val parameters = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .filter(parameter => parameter.name == "value" || parameter.name == "captured")
      .toVector
    assertEquals(Vector("value", "captured"), parameters.map(_.name))
    assertTrue(parameters.forall(_.isCallByNameParameter))
    assertEquals(Vector(Some("String"), Some("String")), parameters.map(_.typeElement.map(_.getText)))
    assertEquals(
      Vector("MetallurgyParameterType", "MetallurgyParameterType"),
      parameters.map(_.typeElement.get.getParent.getClass.getSimpleName)
    )
    assertEquals(
      Vector(ScalaTokenType.PureFunctionArrow, ScalaTokenType.PureFunctionArrow),
      parameters.map(_.typeElement.get.getParent.getNode.getFirstChildNode.getElementType)
    )
    assertEquals(
      Vector("{x}"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureSet]).asScala.toVector.map(_.getText)
    )

  def testPureArrowsWithoutExplicitSetsAndMixedAnnotationsKeepDistinctRoles(): Unit =
    val source =
      """class Capability extends caps.Capability
        |type Pure = Capability -> String
        |type ContextPure = Capability ?-> String
        |type NullaryPure = () -> String
        |type NestedPure = Capability -> String -> Int
        |type Ordinary = String @unchecked
        |type Mixed = (Capability @unchecked)^
        |def byName(value: -> String): String = value
        |def ordinary(value: => String): String = value
        |""".stripMargin
    val file   = physical("CaptureArrows1.scala", source)

    val functions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionalTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(
      Vector(
        "Capability -> String",
        "Capability ?-> String",
        "() -> String",
        "Capability -> String -> Int",
        "String -> Int"
      ),
      functions.map(_.getText)
    )
    assertTrue(functions.forall(_.isPure))
    assertEquals(Vector(false, true, false, false, false), functions.map(_.isContext))
    assertEquals(
      Vector(
        ScalaTokenType.PureFunctionArrow,
        ScalaTokenType.ImplicitPureFunctionArrow,
        ScalaTokenType.PureFunctionArrow,
        ScalaTokenType.PureFunctionArrow,
        ScalaTokenType.PureFunctionArrow
      ),
      functions.map: function =>
        function.getNode
          .getChildren(null)
          .find(child => child.getText == "->" || child.getText == "?->")
          .get
          .getElementType
    )

    val captureTypes = PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureTypeElement]).asScala.toVector
    assertEquals(Vector("(Capability @unchecked)^"), captureTypes.map(_.getText))
    val annotations  = PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotTypeElement]).asScala.toVector
    assertEquals(Vector("String @unchecked", "Capability @unchecked"), annotations.map(_.getText))
    assertSame(captureTypes.head, annotations.last.getParent.getParent)

    val parameters = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .filter(_.name == "value")
      .toVector
    val byName     = parameters.head
    assertTrue(byName.isCallByNameParameter)
    assertEquals(Some("String"), byName.typeElement.map(_.getText))
    assertEquals("MetallurgyParameterType", byName.typeElement.get.getParent.getClass.getSimpleName)
    val ordinary   = parameters.last
    assertTrue(ordinary.isCallByNameParameter)
    assertEquals(Some("String"), ordinary.typeElement.map(_.getText))
    assertEquals("ScParameterTypeImpl", ordinary.typeElement.get.getParent.getClass.getSimpleName)

  def testCopiesPointersEditsAndMalformedRecoveryRemainDeterministic(): Unit =
    val source      =
      """class Capability extends caps.Capability
        |class Box[A]
        |def value(x: Capability, y: Capability): Box[String]^{x} = ???
        |""".stripMargin
    val file        = physical("CaptureEdits1.scala", source)
    val captureType = PsiTreeUtil.findChildOfType(file, classOf[ScCaptureTypeElement])
    val pointer     = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(captureType)
    val copy        = file.copy()
    val copiedType  = PsiTreeUtil.findChildOfType(copy, classOf[ScCaptureTypeElement])
    assertEquals(source, copy.getText)
    assertEquals(captureType.getText, copiedType.getText)
    assertSame(captureType, captureType.getNavigationElement)

    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val end      = document.getText.indexOf("^{x}") + "^{x".length
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(end, ", y")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("Box[String]^{x, y}", pointer.getElement.getText)
    assertEquals(
      Vector("x", "y"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScCaptureRef]).asScala.toVector.map(_.getText)
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

    val malformedSource  =
      """class Box[A]
        |type Broken = Box[String]^{
        |""".stripMargin
    val malformedPending = myFixture.addFileToProject("src/CaptureMalformed1.scala", malformedSource)
    val malformed        = PsiManager.getInstance(getProject).findFile(malformedPending.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScCaptureTypeElement]).isEmpty)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(malformedPending.getVirtualFile, ParserSyntaxSnapshot.digest(malformedSource))
        .nonEmpty
    )

  def testReadyParserUsesCurrentExactCompilerOptionsAfterSettingsChanges(): Unit =
    val source   =
      """class Capability extends caps.Capability
        |type Captured = Capability^
        |""".stripMargin
    val file     = physical("CaptureOptionRefresh1.scala", source)
    val original = ScalaPluginSemanticBridge.additionalCompilerOptions(getModule)
    assertTrue(original.contains("-language:experimental.captureChecking"))
    try
      ScalaPluginSemanticBridge.setAdditionalCompilerOptions(
        getModule,
        original.filterNot(_ == "-language:experimental.captureChecking")
      )
      FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
      val missing = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
      assertTrue(PsiTreeUtil.findChildrenOfType(missing, classOf[ScCaptureTypeElement]).isEmpty)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
          .nonEmpty
      )

      ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, original)
      FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
      val restored = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
      assertEquals(
        Vector("Capability^"),
        PsiTreeUtil.findChildrenOfType(restored, classOf[ScCaptureTypeElement]).asScala.toVector.map(_.getText)
      )
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
          .isEmpty
      )
    finally ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, original)

  private def physical(name: String, source: String): com.intellij.psi.PsiFile =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    file

  private def assertDirectChildren(element: PsiElement, source: String): Unit =
    assertEquals(element.getText, element.getTextRange.substring(source))
    val children = element.getNode.getChildren(null).toVector
    assertFalse(element.getText, children.isEmpty)
    assertEquals(element.getText, children.map(_.getText).mkString)
    children.foreach(child => assertSame(element.getNode, child.getTreeParent))

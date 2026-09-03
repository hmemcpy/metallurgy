package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScEnumCases
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScEnumClassCase
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScEnumSingletonCase
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScEnum
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait
import org.junit.Assert.{assertEquals, assertTrue}
import scala.jdk.CollectionConverters.*

private[psiproducer] trait Scala3PackagePsiDefinitionMountTests extends Scala3PackagePsiProducerTestSupport:
  def testSimpleOwnerTypeMountsUseNativePhysicalPsi(): Unit =
    assertEquals(
      Math.addExact(org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition.FileNodeType.getStubVersion, 15),
      Scala3DotcParserDefinition.FileNodeType.getStubVersion
    )
    assertEquals(
      "95041533f2b134954b299076b35b97be15ec3c8a07174004a0cc23f0ac462053",
      Scala3DotcFileElementType.PersistenceSchemaFingerprint
    )
    assertEquals(
      "d3fce0b5e0cbf9d35b56c1ba9b94a670f7b2678556e8f670d351bde5591e3fd5",
      Scala3DotcFileElementType.CatalogPlanFingerprint
    )
    val source  =
      """trait B
        |class C[A](x: A) extends B:
        |  self: B =>
        |  def value: A = x
        |""".stripMargin
    val pending = codeInsightFixture.addFileToProject("src/OwnerTypeMountCase.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(
      Vector("A"),
      PsiTreeUtil
        .findChildrenOfType(file, classOf[org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass])
        .asScala
        .toVector
        .filter(_.name == "C")
        .flatMap(_.parameters)
        .flatMap(_.typeElement)
        .map(_.getText)
    )
    assertEquals(
      Vector("B"),
      PsiTreeUtil
        .findChildrenOfType(
          file,
          classOf[org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateParents]
        )
        .asScala
        .toVector
        .flatMap(_.typeElements)
        .map(_.getText)
    )
    assertEquals(
      Vector("B"),
      PsiTreeUtil
        .findChildrenOfType(
          file,
          classOf[org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSelfTypeElement]
        )
        .asScala
        .toVector
        .flatMap(_.typeElement)
        .map(_.getText)
    )

  def testParentlessTemplateDefinitionsUseNativePhysicalPsi(): Unit =
    val source      =
      """class C
        |trait T
        |object O
        |class Explicit()
        |class Braced {
        |  trait Nested
        |}
        |enum E:
        |  case A
        |  case B()
        |end E
        |""".stripMargin
    val pending     = codeInsightFixture.addFileToProject("src/ParentlessTemplateCase.scala", source)
    val file        = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val classes     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScClass])
      .asScala
      .filterNot(_.isInstanceOf[ScEnum | ScEnumClassCase])
      .map(_.name)
      .toVector
      .sorted
    val failure     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    assertEquals(Vector("Braced", "C", "Explicit"), classes)
    assertEquals(
      Vector("Nested", "T"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScTrait]).asScala.map(_.name).toVector.sorted
    )
    assertEquals(
      Vector("O"),
      PsiTreeUtil
        .findChildrenOfType(file, classOf[ScObject])
        .asScala
        .filterNot(_.isInstanceOf[ScEnumSingletonCase])
        .map(_.name)
        .toVector
    )
    val enumeration = PsiTreeUtil.findChildOfType(file, classOf[ScEnum])
    assertEquals("E", enumeration.name)
    assertEquals(
      Vector("A"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumSingletonCase]).asScala.map(_.name).toVector
    )
    assertEquals(
      Vector("B"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumClassCase]).asScala.map(_.name).toVector
    )
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumCases]).size)
    assertEquals(5, PsiTreeUtil.findChildrenOfType(file, classOf[ScPrimaryConstructor]).size)
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScTemplateBody]).size)

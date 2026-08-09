package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScAnnotTypeElement,
  ScCompoundTypeElement,
  ScMatchTypeElement,
  ScSimpleTypeElement,
  ScTypeVariableTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAliasDefinition
import org.jetbrains.plugins.scala.lang.psi.stubs.ScTypeAliasStub
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3MatchTypePsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  def testMatchTypesAndCasesUseNativePhysicalPsi(): Unit =
    val source =
      """type lower = String
        |type Element[X] = X match {
        |  case `lower` => Boolean
        |  case Array[t] => t
        |  case String => Char
        |  case _ => Nothing
        |}
        |""".stripMargin
    val file   = physical("MatchTypes1.scala", source)

    val alias   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeAliasDefinition])
      .asScala
      .find(_.name == "Element")
      .get
    val matched = PsiTreeUtil.findChildOfType(file, classOf[ScMatchTypeElement])
    assertEquals("Element", alias.name)
    assertEquals(source.substring(source.indexOf("X match"), source.length - 1), matched.getText)
    assertSame(alias, matched.getParent)
    assertEquals("X", matched.scrutineeTypeElement.getText)
    assertSame(matched, matched.scrutineeTypeElement.getParent)

    val cases = matched.cases.get
    assertSame(matched, cases.getParent)
    assertEquals(4, cases.cases.size)
    assertSame(cases.firstCase, cases.cases.head)
    assertEquals(Vector("`lower`", "Array[t]", "String", "_"), cases.cases.flatMap(_.pattern).map(_.getText))
    assertEquals(Vector("Boolean", "t", "Char", "Nothing"), cases.cases.flatMap(_.result).map(_.getText))
    assertTrue(cases.firstCase.pattern.get.isInstanceOf[ScSimpleTypeElement])
    assertFalse(cases.firstCase.pattern.get.isInstanceOf[ScTypeVariableTypeElement])
    cases.cases.foreach: matchCase =>
      assertSame(cases, matchCase.getParent)
      assertSame(matchCase, matchCase.pattern.get.getParent)
      assertSame(matchCase, matchCase.result.get.getParent)
      assertPhysicalContract(matchCase, source)

    val variables = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeVariableTypeElement])
      .asScala
      .toVector
    assertEquals(Vector("t", "_"), variables.map(_.getText))
    assertPhysicalContract(cases, source)
    assertPhysicalContract(matched, source)

  def testMatchTypesMountRecursivelyInAdmittedTypeOwners(): Unit =
    val source =
      """class Box[A]
        |type Nested[X] = Box[X match { case String => Char; case _ => Nothing }]
        |class Parameter[X](value: X match { case Int => Long; case _ => X })
        |trait Owners[X]:
        |  type Bounded[A <: X match { case String => Char; case _ => X }] = A
        |  def result: X match
        |    case Array[t] => t match { case String => Char; case _ => t }
        |    case _ => X
        |  val property: X match { case String => Char; case _ => X }
        |""".stripMargin
    val file   = physical("MatchTypeOwners1.scala", source)

    val matches = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScMatchTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(6, matches.size)
    assertEquals(Vector(2, 2, 2, 2, 2, 2), matches.map(_.cases.get.cases.size))
    assertTrue(matches.exists(_.getText.contains("\n    case Array[t] => t match")))
    matches.foreach: matched =>
      assertSame(matched, matched.scrutineeTypeElement.getParent)
      assertSame(matched, matched.cases.get.getParent)
      assertPhysicalContract(matched, source)

  def testCopiesPointersEditsAndMalformedRecoveryRemainDeterministic(): Unit =
    val source  =
      """type Element[X] = X match {
        |  case String => Char
        |  case _ => Nothing
        |}
        |""".stripMargin
    val file    = physical("MatchTypeEdits1.scala", source)
    val matched = PsiTreeUtil.findChildOfType(file, classOf[ScMatchTypeElement])
    val pointer = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(matched)
    val copy    = file.copy()
    assertEquals(source, copy.getText)
    assertEquals(matched.getText, PsiTreeUtil.findChildOfType(copy, classOf[ScMatchTypeElement]).getText)
    assertSame(matched, matched.getNavigationElement)

    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val start    = document.getText.indexOf("String")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(start, start + "String".length, "Array[t]")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertTrue(pointer.getElement.getText.contains("case Array[t] => Char"))
    assertEquals(
      Vector("t", "_"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeVariableTypeElement]).asScala.map(_.getText).toVector
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

    val malformedSource  = "type Broken[X] = X match\n  case String =>\n"
    val malformedPending = myFixture.addFileToProject("src/MatchTypeMalformed1.scala", malformedSource)
    val malformed        = PsiManager.getInstance(getProject).findFile(malformedPending.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScTypeAliasDefinition]).isEmpty)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(malformedPending.getVirtualFile, ParserSyntaxSnapshot.digest(malformedSource))
        .nonEmpty
    )

  def testMatchTypeAliasStubsSerializeAndReopenWithoutMatchRoleStubs(): Unit =
    val source  =
      """type Element[X] = X match {
        |  case String => Char
        |  case _ => Nothing
        |}
        |""".stripMargin
    val file    = physical("MatchTypePersistence1.scala", source).asInstanceOf[PsiFileImpl]
    val tree    = file.calcStubTree
    val aliases = tree.getPlainList.asScala.collect { case stub: ScTypeAliasStub => stub }.toVector
    assertEquals(Vector("Element"), aliases.map(_.getName))
    assertEquals(Vector(Some("X match {\n  case String => Char\n  case _ => Nothing\n}")), aliases.map(_.typeText))
    assertFalse(tree.getPlainList.asScala.exists(_.getClass.getName.contains("MatchType")))

    val output          = ByteArrayOutputStream()
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored        = StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    val restoredAliases = restored.getPlainList.asScala.collect { case stub: ScTypeAliasStub => stub }.toVector
    assertEquals(
      aliases.map(stub => stub.getName -> stub.typeText),
      restoredAliases.map(stub => stub.getName -> stub.typeText)
    )
    file.setTreeElementPointer(null)
    assertEquals(null, file.getTreeElement)
    val reopenedAliases = file.getStubTree.getPlainList.asScala.collect { case stub: ScTypeAliasStub => stub }.toVector
    assertEquals(
      restoredAliases.map(stub => stub.getName -> stub.typeText),
      reopenedAliases.map(stub => stub.getName -> stub.typeText)
    )

  def testTermCasesAndCaptureTypesRemainFailClosedWhileNewTypeFamiliesMount(): Unit =
    val refinement = physical(
      "MatchTypeRefinement1.scala",
      "type Structural[X] = X match\n  case String => Char { type Member }\n"
    )
    assertEquals(1, PsiTreeUtil.findChildrenOfType(refinement, classOf[ScCompoundTypeElement]).size)

    val annotated = physical(
      "MatchTypeAnnotated1.scala",
      "type Annotated[X] = X match\n  case String => (Char @unchecked)\n"
    )
    assertEquals(1, PsiTreeUtil.findChildrenOfType(annotated, classOf[ScAnnotTypeElement]).size)

    Vector(
      "val result = 1 match\n  case 1 => 2\n",
      "type Deferred[X] = X match\n  case String => Char^\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/MatchTypeBoundary$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDefinition]).isEmpty)
      assertTrue(
        source,
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
          .nonEmpty
      )

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

  private def assertPhysicalContract(element: PsiElement, source: String): Unit =
    assertEquals(element.getText, element.getTextRange.substring(source))
    val children = element.getNode.getChildren(null).toVector
    assertFalse(element.getText, children.isEmpty)
    assertEquals(element.getText, children.map(_.getText).mkString)
    children.foreach(child => assertSame(element.getNode, child.getTreeParent))

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.lexer.{ScalaTokenType, ScalaTokenTypes}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParameterizedTypeElement, ScWildcardTypeElement}
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.{
  ScParameterizedTypeElementImpl,
  ScTypeArgsImpl,
  ScWildcardTypeElementImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternWildcardTypePsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  private def descendants[T <: PsiElement: ClassTag](file: PsiFile): Vector[T] =
    PsiTreeUtil
      .findChildrenOfType(file, implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]])
      .asScala
      .toVector

  private def physical(name: String, source: String) =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    file

  private def pendingFile(name: String, source: String) =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    // Forces the deferred PSI parse before the capability failure is read.
    val _       = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement])
    file

  private def wildcardTypeElements(file: PsiFile): Vector[ScWildcardTypeElementImpl] =
    descendants[ScWildcardTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)

  private def markerLeaf(element: ScWildcardTypeElement, text: String): PsiElement =
    val leaves = PsiTreeUtil.findChildrenOfType(element, classOf[PsiElement]).asScala.toVector
    leaves
      .find(child => child.getText == text && child.getFirstChild == null)
      .getOrElse(throw new AssertionError(s"marker leaf '$text' not found in '${element.getText}'"))

  private def assertPhysicalContract(element: PsiElement, source: String): Unit =
    assertEquals(element.getText, element.getTextRange.substring(source))
    val children = element.getNode.getChildren(null).toVector
    assertTrue(element.getText, children.nonEmpty)
    assertEquals(element.getText, children.map(_.getText).mkString)
    assertEquals(element.getTextRange.getStartOffset, children.head.getStartOffset)
    assertEquals(element.getTextRange.getEndOffset, children.last.getStartOffset + children.last.getTextLength)
    children
      .sliding(2)
      .foreach:
        case Vector(left, right) => assertEquals(left.getStartOffset + left.getTextLength, right.getStartOffset)
        case _                   => ()
    children.foreach(child => assertSame(element.getNode, child.getTreeParent))

  @Test
  def testMatchOwnedWildcardTypesProduceNativePsiAcrossSpellingsAndBoundStates(): Unit =
    val source         =
      """def wildcards(x: Any): Any = x match
        |  case y: Box[?] => "unbounded"
        |  case y: Box[? >: A] => "lower"
        |  case y: Box[? <: A] => "upper"
        |  case y: Box[? >: A <: B] => "both"
        |  case y: Box[_] => "u-unbounded"
        |  case y: Box[_ >: A] => "u-lower"
        |  case y: Box[_ <: A] => "u-upper"
        |  case y: Box[_ >: A <: B] => "u-both"
        |""".stripMargin
    val file           = physical("MatchWildcardTypes1.scala", source)
    assertEquals(8, descendants[ScCaseClauseImpl](file).size)
    val typed          = descendants[Sc3TypedPatternImpl](file)
    assertEquals(8, typed.size)
    val wildcards      = wildcardTypeElements(file)
    assertEquals(8, wildcards.size)
    val expectedBounds = Vector(
      (None, None),
      (Some("A"), None),
      (None, Some("A")),
      (Some("A"), Some("B")),
      (None, None),
      (Some("A"), None),
      (None, Some("A")),
      (Some("A"), Some("B"))
    )
    wildcards.zipWithIndex.foreach { case (wildcard, index) =>
      val (lower, upper) = expectedBounds(index)
      assertEquals(lower, wildcard.lowerTypeElement.map(_.getText))
      assertEquals(upper, wildcard.upperTypeElement.map(_.getText))
      assertTrue(wildcard.isInstanceOf[ScWildcardTypeElementImpl])
      val markerText     = if index < 4 then "?" else "_"
      val marker         = markerLeaf(wildcard, markerText)
      val expectedMarker = if index < 4 then ScalaTokenType.WildcardTypeQuestionMark else ScalaTokenTypes.tUNDER
      assertEquals(expectedMarker, marker.getNode.getElementType)
      assertSame(wildcard, marker.getParent)
      wildcard.getParent match
        case wrapper: ScTypePattern           =>
          assertEquals(wildcard.getTextRange, wrapper.getTextRange)
        case args: ScParameterizedTypeElement =>
          assertEquals(wildcard.getTextRange.getEndOffset, args.getTextRange.getEndOffset - 1)
        case args: ScTypeArgsImpl             =>
          assertTrue(
            s"wildcard must sit inside its applied arguments: ${args.getText}",
            args.getText.contains(wildcard.getText)
          )
        case other                            =>
          fail(s"unexpected wildcard parent: ${other.getClass.getName}")
      assertPhysicalContract(wildcard, source)
    }
    assertEquals(1, descendants[ScMatchImpl](file).size)

  @Test
  def testMatchOwnedWildcardTypesOwnGivenPatternPositions(): Unit =
    val source    =
      """def givens(x: Any): Any = x match
        |  case given Box[? <: A] => "anon"
        |  case ord @ given Box[_ >: A <: B] => "named"
        |  case given Option[? >: A] => "applied"
        |  case _ @ given Box[? >: A] => "wildcardBinder"
        |  case `back-tick` @ given Box[? <: A] => "backtickedBinder"
        |""".stripMargin
    val file      = physical("MatchWildcardTypesGiven.scala", source)
    val givens    = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(5, givens.size)
    assertEquals(
      Vector(
        "given Box[? <: A]",
        "given Box[_ >: A <: B]",
        "given Option[? >: A]",
        "given Box[? >: A]",
        "given Box[? <: A]"
      ),
      givens.map(_.getText)
    )
    val wildcards = wildcardTypeElements(file)
    assertEquals(5, wildcards.size)
    val markers   =
      wildcards.map(w => markerLeaf(w, if w.getText.startsWith("?") then "?" else "_").getNode.getElementType)
    assertEquals(
      Vector(
        ScalaTokenType.WildcardTypeQuestionMark,
        ScalaTokenTypes.tUNDER,
        ScalaTokenType.WildcardTypeQuestionMark,
        ScalaTokenType.WildcardTypeQuestionMark,
        ScalaTokenType.WildcardTypeQuestionMark
      ),
      markers
    )
    assertEquals(5, descendants[ScCaseClauseImpl](file).size)

  @Test
  def testMatchOwnedWildcardBoundsCloseOverOwnedTypeFamilies(): Unit =
    val source    =
      """def bounds(x: Any): Any = x match
        |  case y: Box[? <: Box[A]] => "applied"
        |  case y: Box[? >: (A, B)] => "tuple"
        |  case y: Box[Option[? <: A]] => "nested"
        |  case y: Box[(Option[? >: A], B)] => "nested-tuple"
        |  case y: Box[? <: Option[? <: A]] => "nested-bounded-q"
        |  case y: Box[_ <: Option[_ >: A]] => "nested-bounded-u"
        |  case y: Box[? >: A <: Option[? >: A <: B]] => "nested-bounded-both"
        |  case y: Box[A, ? <: B, Option[C]] => "mixed"
        |  case y: Box[
        |      /* c1 */ ? <: A // c2
        |    ] => "trivia"
        |""".stripMargin
    val file      = physical("MatchWildcardTypesBounds.scala", source)
    assertEquals(9, descendants[ScCaseClauseImpl](file).size)
    val wildcards = wildcardTypeElements(file)
    assertEquals(12, wildcards.size)
    assertEquals(
      Vector(
        "Box[A]",
        "(A, B)",
        "A",
        "A",
        "Option[? <: A]",
        "A",
        "Option[_ >: A]",
        "A",
        "Option[? >: A <: B]",
        "B",
        "B",
        "A"
      ),
      wildcards.map(w => w.upperTypeElement.orElse(w.lowerTypeElement).map(_.getText).getOrElse(""))
    )
    val applied   = descendants[ScParameterizedTypeElementImpl](file).map(_.getText)
    assertTrue(applied.contains("Box[(Option[? >: A], B)]"))
    assertTrue(applied.contains("Option[? <: A]"))
    wildcards.foreach(w => assertPhysicalContract(w, source))

  @Test
  def testMatchOwnedWildcardTypesStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source   =
      """def persisted(x: Any): Any = x match
        |  case y: Box[? <: A] => "wildcard"
        |  case y: Box[_ >: A] => "legacy"
        |""".stripMargin
    val file     = physical("MatchWildcardTypesPersisted.scala", source)
    val fileInfo = file.asInstanceOf[PsiFileImpl]
    val stubTree = fileInfo.calcStubTree
    val stubList = stubTree.getPlainList.asScala.toVector
    assertTrue(
      "wildcard types must not create stubs",
      stubList.forall(stub => !stub.getClass.getSimpleName.toLowerCase.contains("wildcard"))
    )
    val output   = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(stubTree.getRoot, output)
    val restored = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(stubList.map(_.getClass.getName), restored.getPlainList.asScala.toVector.map(_.getClass.getName))
    fileInfo.setTreeElementPointer(null)
    assertEquals(null, fileInfo.getTreeElement)
    val reparsed = wildcardTypeElements(file)
    assertEquals(Vector("? <: A", "_ >: A"), reparsed.map(_.getText))

  @Test
  def testMatchOwnedWildcardTypeEditsTransitionBetweenSpellingsBoundsAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: Box[WILDCARD] => "w"
        |""".stripMargin
    val file                               = physical("MatchWildcardTypesEdit.scala", template.replace("WILDCARD", "?"))
    assertEquals(Vector("?"), wildcardTypeElements(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("WILDCARD", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("_")
    assertEquals(Vector("_"), wildcardTypeElements(file).map(_.getText))
    replace("? <: A")
    assertEquals(Vector("? <: A"), wildcardTypeElements(file).map(_.getText))
    assertEquals(Some("A"), wildcardTypeElements(file).head.upperTypeElement.map(_.getText))
    replace("_ >: A <: B")
    assertEquals(
      (Some("A"), Some("B")),
      (
        wildcardTypeElements(file).head.lowerTypeElement.map(_.getText),
        wildcardTypeElements(file).head.upperTypeElement.map(_.getText)
      )
    )
    replace("? <: A >: B")
    assertTrue(wildcardTypeElements(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("Box[A]")
    assertEquals(Vector.empty, wildcardTypeElements(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)

  @Test
  def testMatchOwnedWildcardTypeFailClosedShapesStayUncoveredWithoutPartialPsi(): Unit =
    val sources = Vector(
      """def pending(x: Any): Any = x match
        |  case y: ? => "bare"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case ? => "bareRoot"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: _ => "bareLegacy"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: (?, B) => "component"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: Box[? <: ?] => "nestedBare"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: Box[? <: A >: B] => "reversed"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: Box[? <: A <: B] => "duplicate"
        |""".stripMargin,
      """def partial(x: Any): Any = { case y: Box[? <: A] => 1 }""",
      """def catcher(x: Any): Any = try x catch { case y: Box[? <: A] => 1 }""",
      """def gen(xs: List[Any]): List[Any] = for (y: Box[? <: A]) <- xs yield y""",
      """def quoted(x: Any): Any = '{ case y: Box[? <: A] => 1 }""",
      """def pending(x: Any): Any = x match
        |  case y: Box[? <: A] => 1
        |  case z: p.A => 2
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchWildcardUnsupported$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported wildcard shape should fail closed (source $index)", failure.isDefined)
      assertTrue(wildcardTypeElements(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    }

  @Test
  def testOrdinaryWildcardTypeOwnerStaysUnchangedOutsideMatchScope(): Unit =
    val source    =
      """class C:
        |  def m(v: Box[? <: A]): Box[? <: A] = v
        |""".stripMargin
    val file      = physical("MatchWildcardOrdinary.scala", source)
    val wildcards = wildcardTypeElements(file)
    assertEquals(2, wildcards.size)
    wildcards.foreach(w => assertEquals("? <: A", w.getText))
    assertEquals(None, wildcards.head.lowerTypeElement.map(_.getText))
    assertEquals(Some("A"), wildcards.head.upperTypeElement.map(_.getText))
    assertTrue(descendants[ScMatchImpl](file).isEmpty)

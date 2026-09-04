package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScNamedTupleTypeComponent,
  ScNamedTupleTypeElement,
  ScTupleTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScSimpleTypeElementImpl
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  Sc3TypedPatternImpl,
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  ScNamingPatternImpl,
  ScTuplePatternImpl
}
import org.junit.Assert.*
import org.junit.Test

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

// Named tuple types in typed patterns are rejected by the exact compiler during pattern
// typing on both supported versions, so the whole family stays fail closed. These tests
// pin that boundary: every measured spelling fails the file with no partial match-family
// PSI, while the compiler-valid alias and term-pattern routes stay on their existing
// owners unchanged.
final class Scala3MatchNamedTupleTypeFailClosedPsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  private def descendants[T <: PsiElement: ClassTag](file: PsiFile): Vector[T] =
    PsiTreeUtil
      .findChildrenOfType(file, implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]])
      .asScala
      .toVector

  private def pendingFile(name: String, source: String): PsiFile =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    val _       = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement])
    file

  @Test
  def testNamedTupleTypedPatternSpellingsFailClosed(): Unit =
    val shapes = Vector(
      """def direct(x: Any): Any = x match
        |  case y: (a: A, b: B) => 1
        |""".stripMargin,
      """def wrapped(x: Any): Any = x match
        |  case y: ((a: A, b: B)) => 1
        |""".stripMargin,
      """def applied(x: Any): Any = x match
        |  case y: Box[(a: A, b: B)] => 1
        |""".stripMargin,
      """def bounded(x: Any): Any = x match
        |  case y: Box[? <: (a: A, b: B)] => 1
        |""".stripMargin,
      """def givenAnon(x: Any): Any = x match
        |  case given (a: A, b: B) => 1
        |""".stripMargin,
      """def single(x: Any): Any = x match
        |  case y: (a: A) => 1
        |""".stripMargin
    )
    shapes.zipWithIndex.foreach { case (body, index) =>
      val source  = "class A; class B; class Box[T]\n" + body
      val file    = pendingFile(s"MatchNamedTupleFailClosed$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(
        s"named tuple typed pattern must fail closed (shape $index): $failure",
        failure.isDefined
      )
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)
      assertTrue(descendants[ScTupleTypeElement](file).isEmpty)
      assertTrue(descendants[ScTuplePatternImpl](file).isEmpty)
      assertTrue(descendants[ScNamingPatternImpl](file).isEmpty)
      assertTrue(descendants[ScNamedTupleTypeElement](file).isEmpty)
      assertTrue(descendants[ScNamedTupleTypeComponent](file).isEmpty)
      assertTrue(descendants[ScTypePattern](file).isEmpty)
      assertTrue(
        descendants[ScStableCodeReferenceImpl](file).forall(ref => ref.getText != "a" && ref.getText != "b")
      )
      assertTrue(
        descendants[ScSimpleTypeElementImpl](file).forall(element => element.getText != "A" && element.getText != "B")
      )
    }

  @Test
  def testMixedNamedTupleAndAdmittedFileFailsAtomically(): Unit =
    val source  =
      """class A; class B
        |def mixed(x: Any): Any = x match
        |  case y: (A | B) => "admitted"
        |  case y: (a: A, b: B) => "namedTuple"
        |""".stripMargin
    val file    = pendingFile("MatchNamedTupleMixedAtomicity.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"mixed file must fail the whole file closed: $failure", failure.isDefined)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(
      descendants[ScStableCodeReferenceImpl](file).forall(ref => ref.getText != "a" && ref.getText != "b")
    )
    assertTrue(
      descendants[ScSimpleTypeElementImpl](file).forall(element => element.getText != "A" && element.getText != "B")
    )
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[ScTupleTypeElement](file).isEmpty)
    assertTrue(descendants[ScNamedTupleTypeElement](file).isEmpty)
    assertTrue(descendants[ScNamedTupleTypeComponent](file).isEmpty)
    assertTrue(descendants[ScTypePattern](file).isEmpty)
    assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)
    assertTrue(descendants[ScTuplePatternImpl](file).isEmpty)
    assertTrue(descendants[ScNamingPatternImpl](file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    assertTrue(
      descendants[ScSimpleTypeElementImpl](file).forall(element => element.getText != "A" && element.getText != "B")
    )
    assertTrue(
      descendants[ScStableCodeReferenceImpl](file).forall(ref => ref.getText != "a" && ref.getText != "b")
    )

  @Test
  def testAliasRouteStaysAdmittedOnTypeIdentOwner(): Unit =
    val source             =
      """class A; class B
        |type NT = (a: A, b: B)
        |def aliased(x: Any): Any = x match
        |  case y: NT => "alias"
        |  case given NT => "givenAlias"
        |""".stripMargin
    val pending            = myFixture.addFileToProject("src/MatchNamedTupleAlias.scala", source)
    val file               = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure            = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(1, descendants[ScGivenPatternImpl](file).size)
    val aliasReferences    = descendants[ScStableCodeReferenceImpl](file).filter(_.getText == "NT")
    assertEquals(2, aliasReferences.size)
    val matchElement       = descendants[ScMatchImpl](file).head
    assertTrue(
      PsiTreeUtil.findChildrenOfType(matchElement, classOf[ScNamedTupleTypeElement]).isEmpty
    )
    assertTrue(
      PsiTreeUtil.findChildrenOfType(matchElement, classOf[ScNamedTupleTypeComponent]).isEmpty
    )
    val definitionElements = descendants[ScNamedTupleTypeElement](file)
    assertEquals(1, definitionElements.size)
    assertEquals("(a: A, b: B)", definitionElements.head.getText)
    assert(
      PsiTreeUtil.getParentOfType(definitionElements.head, classOf[ScMatchImpl]) == null,
      "the file-scope named tuple definition must own its element outside any match"
    )
    val components         = descendants[ScNamedTupleTypeComponent](file)
    assertEquals(Vector("a: A", "b: B"), components.map(_.getText).sorted)
    aliasReferences.foreach { reference =>
      val typed        = PsiTreeUtil.getParentOfType(reference, classOf[Sc3TypedPatternImpl])
      val givenPattern = PsiTreeUtil.getParentOfType(reference, classOf[ScGivenPatternImpl])
      assert(
        typed != null || givenPattern != null,
        "alias reference must sit under a typed or given pattern on the type-ident route"
      )
    }

  @Test
  def testTermTuplePatternsStayAdmittedUnchanged(): Unit =
    val source        =
      """class A; class B
        |def terms(x: (A, B)): Any = x match
        |  case (p: A, q: B) => 2
        |  case (p, q) => 3
        |""".stripMargin
    val pending       = myFixture.addFileToProject("src/MatchNamedTupleTerms.scala", source)
    val file          = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure       = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    val tuplePatterns = descendants[ScTuplePatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(2, tuplePatterns.size)
    tuplePatterns.foreach { pattern =>
      val clause = PsiTreeUtil.getParentOfType(pattern, classOf[ScCaseClauseImpl])
      assert(clause != null, "term tuple pattern must sit under its case clause")
      assertEquals("(p: A, q: B)", tuplePatterns.head.getText)
      assertEquals("(p, q)", tuplePatterns.last.getText)
    }
    assertTrue(descendants[ScNamedTupleTypeElement](file).isEmpty)
    assertTrue(descendants[ScNamedTupleTypeComponent](file).isEmpty)

  @Test
  def testNamedAssignmentTermPatternsStayFailClosed(): Unit =
    val source  =
      """class A; class B
        |def named(x: (A, B)): Any = x match
        |  case (a = p, b = q) => 1
        |""".stripMargin
    val file    = pendingFile("MatchNamedTupleAssignmentTerm.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"named assignment term pattern must fail closed: $failure", failure.isDefined)
    assertTrue(descendants[ScTuplePatternImpl](file).isEmpty)
    assertTrue(descendants[ScNamedTupleTypeElement](file).isEmpty)
    assertTrue(descendants[ScNamedTupleTypeComponent](file).isEmpty)
    assertTrue(descendants[ScTypePattern](file).isEmpty)
    assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(
      descendants[ScStableCodeReferenceImpl](file).forall(ref => ref.getText != "a" && ref.getText != "b")
    )
    assertTrue(
      descendants[ScSimpleTypeElementImpl](file).forall(element => element.getText != "A" && element.getText != "B")
    )
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[ScTupleTypeElement](file).isEmpty)
    assertTrue(descendants[ScNamingPatternImpl](file).isEmpty)

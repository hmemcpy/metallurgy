package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiErrorElement, PsiManager}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScBlock, ScGuard, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCompositePatternImpl,
  ScConstructorPatternImpl,
  ScLiteralPatternImpl,
  ScNamingPatternImpl,
  ScReferencePatternImpl,
  ScSeqWildcardPatternImpl,
  ScStableReferencePatternImpl,
  ScTuplePatternImpl,
  ScWildcardPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.Sc3TypedPatternImpl
import org.jetbrains.plugins.scala.lang.psi.impl.expr.{ScBlockImpl, ScMatchImpl}
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{ScCaseClauseImpl, ScCaseClausesImpl}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import com.intellij.psi.PsiElement
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchExpressionPsiTest extends Scala3CompatTestCase:

  private def descendants[T <: PsiElement: ClassTag](file: com.intellij.psi.PsiFile): Vector[T] =
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

  @Test
  def testIndentedMatchProducesNativeMatchCaseClausesAndPatterns(): Unit =
    val source                =
      """def classify(x: Any): Any = x match
        |  case _ => "wildcard"
        |  case v => v
        |  case 42 => "int"
        |""".stripMargin
    val file                  = physical("MatchIndented.scala", source)
    val matches               = descendants[ScMatchImpl](file)
    assertEquals(1, matches.size)
    val matched               = matches.head
    assertEquals(
      """x match
        |  case _ => "wildcard"
        |  case v => v
        |  case 42 => "int"""".stripMargin,
      matched.getText
    )
    val caseClausesContainers = descendants[ScCaseClausesImpl](file)
    assertEquals(1, caseClausesContainers.size)
    val clauses               = descendants[ScCaseClauseImpl](file)
    assertEquals(3, clauses.size)
    assertEquals(
      Vector("""case _ => "wildcard"""", "case v => v", """case 42 => "int""""),
      clauses.map(_.getText)
    )
    assertEquals(1, descendants[ScWildcardPatternImpl](file).size)
    assertEquals(1, descendants[ScReferencePatternImpl](file).size)
    assertEquals(1, descendants[ScLiteralPatternImpl](file).size)
    val selectors             = descendants[ScReferenceExpression](file).filter(_.getText == "x")
    assertEquals(1, selectors.size)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)
    clauses.foreach: clause =>
      assertTrue(clause.getText, clause.getText.startsWith("case "))
      assertEquals(source.indexOf(clause.getText), clause.getTextRange.getStartOffset)

  @Test
  def testBracedMatchSharesTheNativeRoute(): Unit =
    val source  = "def braced(y: Any): Any = y match { case 1 => 2; case _ => 3 }"
    val file    = physical("MatchBraced.scala", source)
    val matches = descendants[ScMatchImpl](file)
    assertEquals(1, matches.size)
    assertEquals(source.stripPrefix("def braced(y: Any): Any = "), matches.head.getText)
    assertEquals(1, descendants[ScCaseClausesImpl](file).size)
    assertEquals(2, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScLiteralPatternImpl](file).size)
    assertEquals(1, descendants[ScWildcardPatternImpl](file).size)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)

  @Test
  def testGuardedCaseClauseKeepsGuardOutsideThePatternAndBody(): Unit =
    val source      =
      """def guarded(x: Any): Any = x match
        |  case v if v == 0 => v
        |  case _ => "other"
        |""".stripMargin
    val file        = physical("MatchGuarded.scala", source)
    val clauses     = descendants[ScCaseClauseImpl](file)
    assertEquals(2, clauses.size)
    val guardClause = clauses.find(_.getText.startsWith("case v if")).get
    val guardPsi    = PsiTreeUtil.findChildOfType(guardClause, classOf[ScGuard])
    assertTrue("guarded case clause should have an ScGuard child", guardPsi != null)
    assertEquals("v == 0", guardPsi.getText)
    assertEquals(2, descendants[ScBlockImpl](file).size)

  @Test
  def testPatternFamiliesProduceNativePsi(): Unit =
    val source      =
      """def families(x: Any): Any = x match
        |  case typed: String => typed
        |  case named @ Some(namedInner) => named
        |  case (tupleA, tupleB) => tupleB
        |  case 1 | 2 => "alt"
        |  case Some(0) => "zero"
        |  case List(head, tail @ _*) => tail
        |  case Nil => "stable"
        |""".stripMargin
    val file        = physical("MatchPatternFamilies.scala", source)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(7, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    assertEquals(1, descendants[ScStableReferencePatternImpl](file).size)
    assertEquals("Nil", descendants[ScStableReferencePatternImpl](file).head.getText)
    assertEquals(2, descendants[ScNamingPatternImpl](file).size)
    assertEquals(1, descendants[ScTuplePatternImpl](file).size)
    assertEquals(1, descendants[ScCompositePatternImpl](file).size)
    assertEquals(3, descendants[ScConstructorPatternImpl](file).size)
    assertEquals(1, descendants[ScSeqWildcardPatternImpl](file).size)
    val namingNames = descendants[ScNamingPatternImpl](file).map(_.nameId.getText)
    assertEquals(Vector("named", "tail"), namingNames)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testUnsupportedPatternShapesFailClosedAtFileScope(): Unit =
    val source  =
      """def pending(x: Any): Any = x match
        |  case 2.5 => "decimal"
        |""".stripMargin
    val pending = myFixture.addFileToProject("src/MatchUnsupported2.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue("unsupported patterns should cause capability failure", failure.isDefined)
    assertTrue(
      "failure should mention uncovered shape",
      failure.get.toString.contains("UncoveredCompilerShape")
    )

  private def payloadsOutsideMatch(file: com.intellij.psi.PsiFile): Unit =
    val blocks = descendants[ScBlock](file)
    assertTrue(blocks.nonEmpty)
    blocks.foreach: block =>
      assertTrue(
        block.getText,
        block.getChildren.isEmpty || !block.getText.startsWith("case")
      )

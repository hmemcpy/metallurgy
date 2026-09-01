package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.openapi.command.WriteCommandAction
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{
  ScBooleanLiteral,
  ScCharLiteral,
  ScDoubleLiteral,
  ScFloatLiteral,
  ScIntegerLiteral,
  ScLongLiteral,
  ScNullLiteral,
  ScStringLiteral
}
import org.jetbrains.plugins.scala.lang.psi.api.base.ScModifierList
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScGivenPattern
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScPatterns
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeArgs
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.{
  ScParameterizedTypeElementImpl,
  ScSimpleTypeElementImpl,
  ScTypeArgsImpl
}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScBlock, ScGuard, ScReferenceExpression, ScUnitExpr}
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCompositePatternImpl,
  ScConstructorPatternImpl,
  ScGivenPatternImpl,
  ScLiteralPatternImpl,
  ScNamingPatternImpl,
  ScParenthesisedPatternImpl,
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
import org.junit.Assert.{
  assertArrayEquals,
  assertEquals,
  assertFalse,
  assertNotNull,
  assertNull,
  assertSame,
  assertTrue,
  fail
}
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

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
    val source         = "def braced(y: Any): Any = y match { case 1 => 2; case 2.5 => \"dec\"; case 'c' => 3; case _ => 3 }"
    val file           = physical("MatchBraced.scala", source)
    val matches        = descendants[ScMatchImpl](file)
    assertEquals(1, matches.size)
    assertEquals(source.stripPrefix("def braced(y: Any): Any = "), matches.head.getText)
    assertEquals(1, descendants[ScCaseClausesImpl](file).size)
    assertEquals(4, descendants[ScCaseClauseImpl](file).size)
    val bracedLiterals = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(3, bracedLiterals.size)
    assertEquals(Vector("1", "2.5", "'c'"), bracedLiterals.map(_.getText))
    bracedLiterals.foreach: literal =>
      assertEquals(literal.getLiteral, literal.getChildren.head)
      assertEquals(literal.getTextRange, literal.getLiteral.getTextRange)
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
    val source        =
      """def families(x: Any): Any = x match
        |  case typed: String => typed
        |  case named @ Some(namedInner) => named
        |  case (tupleA, tupleB) => tupleB
        |  case 1 | 2 => "alt"
        |  case Some(0) => "zero"
        |  case List(head, tail @ _*) => tail
        |  case Nil => "stable"
        |""".stripMargin
    val file          = physical("MatchPatternFamilies.scala", source)
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
    val namingNames   = descendants[ScNamingPatternImpl](file).map(_.nameId.getText)
    assertEquals(Vector("named", "tail"), namingNames)
    val typedPattern  = descendants[Sc3TypedPatternImpl](file).head
    assertEquals("typed: String", typedPattern.getText)
    assertEquals(source.indexOf("typed: String"), typedPattern.getTextRange.getStartOffset)
    val typePatterns  =
      descendants[org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern](file)
    assertEquals(Vector("String"), typePatterns.map(_.getText))
    assertEquals(typedPattern, typePatterns.head.getParent)
    val argumentLists =
      descendants[org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScPatternArgumentList](file)
    // the argument-list composite covers the compiler subpattern range; the parentheses stay constructor-owned
    assertEquals(Vector("namedInner", "0", "head, tail @ _*"), argumentLists.map(_.getText))
    val constructors  = descendants[ScConstructorPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("Some(namedInner)", "Some(0)", "List(head, tail @ _*)"), constructors.map(_.getText))
    constructors.foreach { constructor =>
      val args = PsiTreeUtil.findChildOfType(
        constructor,
        classOf[org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScPatternArgumentList]
      )
      assertEquals(constructor, args.getParent)
    }
    val composite     = descendants[ScCompositePatternImpl](file).head
    assertEquals("1 | 2", composite.getText)
    assertEquals(2, composite.subpatterns.size)
    val tuple         = descendants[ScTuplePatternImpl](file).head
    assertEquals("(tupleA, tupleB)", tuple.getText)
    assertEquals(source.indexOf("(tupleA, tupleB)"), tuple.getTextRange.getStartOffset)
    val seqNaming     = descendants[ScNamingPatternImpl](file).find(_.nameId.getText == "tail").get
    assertEquals("tail @ _*", seqNaming.getText)
    assertEquals(
      Vector(seqNaming),
      descendants[ScSeqWildcardPatternImpl](file).map(_.getParent)
    )
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testScalarLiteralPatternsProduceNativePsiAtCaseRoot(): Unit =
    val source   =
      """def scalars(x: Any): Any = x match
        |  case 42 => "int"
        |  case 2.5 => "dec"
        |  case 2.5e3 => "sci"
        |  case -2.5 => "negdec"
        |  case 2.5d => "d"
        |  case 2.5D => "Dbig"
        |  case 2.5f => "flt"
        |  case 2.5F => "Fbig"
        |  case -2.5f => "negflt"
        |  case "text" => "str"
        |  case "" => "empty"
        |  case "a\nb" => "esc"
        |  case 'a' => "chr"
        |  case '\n' => "escchr"
        |  case true => "t"
        |  case false => "f"
        |  case -1 => "neg"
        |  case 9999999999 => "big"
        |""".stripMargin
    val file     = physical("MatchScalarLiterals.scala", source)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(18, descendants[ScCaseClauseImpl](file).size)
    val literals = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(18, literals.size)
    assertEquals(
      Vector(
        "42",
        "2.5",
        "2.5e3",
        "-2.5",
        "2.5d",
        "2.5D",
        "2.5f",
        "2.5F",
        "-2.5f",
        "\"text\"",
        "\"\"",
        "\"a\\nb\"",
        "'a'",
        "'\\n'",
        "true",
        "false",
        "-1",
        "9999999999"
      ),
      literals.map(_.getText)
    )
    literals.foreach: literal =>
      assertEquals(source.indexOf(literal.getText), literal.getTextRange.getStartOffset)
    assertEquals(
      (0 until 18).toVector,
      literals.map(literal => descendants[ScCaseClauseImpl](file).indexOf(literal.getParent))
    )
    val byText   = literals.map(literal => literal.getText -> literal).toMap
    assertTrue(byText("42").getLiteral.isInstanceOf[ScIntegerLiteral])
    assertEquals(Integer.valueOf(42), byText("42").getLiteral.getValue)
    assertTrue(byText("2.5").getLiteral.isInstanceOf[ScDoubleLiteral])
    assertEquals(java.lang.Double.valueOf(2.5), byText("2.5").getLiteral.getValue)
    assertTrue(byText("2.5e3").getLiteral.isInstanceOf[ScDoubleLiteral])
    assertEquals(java.lang.Double.valueOf(2500.0), byText("2.5e3").getLiteral.getValue)
    assertTrue(byText("-2.5").getLiteral.isInstanceOf[ScDoubleLiteral])
    assertEquals(java.lang.Double.valueOf(-2.5), byText("-2.5").getLiteral.getValue)
    assertTrue(byText("2.5d").getLiteral.isInstanceOf[ScDoubleLiteral])
    assertTrue(byText("2.5D").getLiteral.isInstanceOf[ScDoubleLiteral])
    assertEquals(java.lang.Double.valueOf(2.5), byText("2.5d").getLiteral.getValue)
    assertEquals(java.lang.Double.valueOf(2.5), byText("2.5D").getLiteral.getValue)
    assertTrue(byText("2.5f").getLiteral.isInstanceOf[ScFloatLiteral])
    assertEquals(java.lang.Float.valueOf(2.5f), byText("2.5f").getLiteral.getValue)
    assertTrue(byText("2.5F").getLiteral.isInstanceOf[ScFloatLiteral])
    assertEquals(java.lang.Float.valueOf(2.5f), byText("2.5F").getLiteral.getValue)
    assertTrue(byText("-2.5f").getLiteral.isInstanceOf[ScFloatLiteral])
    assertEquals(java.lang.Float.valueOf(-2.5f), byText("-2.5f").getLiteral.getValue)
    assertTrue(byText("\"text\"").getLiteral.isInstanceOf[ScStringLiteral])
    assertEquals("text", byText("\"text\"").getLiteral.getValue)
    assertTrue(byText("'a'").getLiteral.isInstanceOf[ScCharLiteral])
    assertEquals(Character.valueOf('a'), byText("'a'").getLiteral.getValue)
    assertTrue(byText("true").getLiteral.isInstanceOf[ScBooleanLiteral])
    assertEquals(java.lang.Boolean.TRUE, byText("true").getLiteral.getValue)
    assertTrue(byText("false").getLiteral.isInstanceOf[ScBooleanLiteral])
    assertEquals(java.lang.Boolean.FALSE, byText("false").getLiteral.getValue)
    assertTrue(byText("-1").getLiteral.isInstanceOf[ScIntegerLiteral])
    assertEquals(Integer.valueOf(-1), byText("-1").getLiteral.getValue)
    assertTrue(byText("9999999999").getLiteral.isInstanceOf[ScIntegerLiteral])
    assertNull("overflow yields the upstream null value", byText("9999999999").getLiteral.getValue)
    assertEquals("Int", byText("9999999999").getLiteral.literalType.canonicalText)
    assertTrue(byText("\"\"").getLiteral.isInstanceOf[ScStringLiteral])
    assertEquals("", byText("\"\"").getLiteral.getValue)
    assertTrue(byText("\"a\\nb\"").getLiteral.isInstanceOf[ScStringLiteral])
    assertEquals("a\nb", byText("\"a\\nb\"").getLiteral.getValue)
    assertTrue(byText("'\\n'").getLiteral.isInstanceOf[ScCharLiteral])
    assertEquals(Character.valueOf('\n'), byText("'\\n'").getLiteral.getValue)
    byText("\"text\"").getLiteral match
      case string: ScStringLiteral =>
        assertEquals("text", string.contentText)
        assertEquals(4, string.contentRange.getLength)
        assertEquals(6, string.getTextRange.getLength)
    byText("42").getLiteral match
      case integer: ScIntegerLiteral =>
        assertEquals("42", integer.contentText)
        assertEquals(integer.getTextRange, integer.contentRange)
    literals.foreach: literal =>
      assertEquals(literal.getTextRange, literal.getLiteral.getTextRange)
    literals.foreach: literal =>
      assertSame(literal, literal.getNavigationElement)
      assertSame(literal.getLiteral, literal.getLiteral.getNavigationElement)
      assertEquals(Vector(literal.getLiteral), literal.getChildren.toVector)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testScalarLiteralPatternsNestThroughOwnedPatternFamilies(): Unit =
    val source        =
      """def nested(x: Any): Any = x match
        |  case Some(2.5) => "ctor"
        |  case ("s", 'c') => "tuple"
        |  case 2.5e3 | "alt" => "alt"
        |  case v @ -1.25f => v
        |  case 9.5: Double => "typed"
        |""".stripMargin
    val file          = physical("MatchScalarNested.scala", source)
    assertEquals(5, descendants[ScCaseClauseImpl](file).size)
    val literals      = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(7, literals.size)
    assertEquals(
      Vector("2.5", "\"s\"", "'c'", "2.5e3", "\"alt\"", "-1.25f", "9.5").sorted,
      literals.map(_.getText).sorted
    )
    val composite     = descendants[ScCompositePatternImpl](file).head
    assertEquals("2.5e3 | \"alt\"", composite.getText)
    assertEquals(2, composite.subpatterns.size)
    val naming        = descendants[ScNamingPatternImpl](file).head
    assertEquals("v @ -1.25f", naming.getText)
    assertEquals(
      Vector(naming),
      literals.filter(_.getText == "-1.25f").map(_.getParent)
    )
    val tuple         = descendants[ScTuplePatternImpl](file).head
    assertEquals("(\"s\", 'c')", tuple.getText)
    val argumentLists =
      descendants[org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScPatternArgumentList](file)
    assertEquals(Vector("2.5"), argumentLists.map(_.getText))
    val typed         = descendants[Sc3TypedPatternImpl](file).head
    assertEquals("9.5: Double", typed.getText)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testScalarLiteralPatternsSurviveCopyEditAndReparse(): Unit =
    val source   =
      """def edited(x: Any): Any = x match
        |  case 2.5 => "dec"
        |""".stripMargin
    val edited   =
      """def edited(x: Any): Any = x match
        |  case -4.5e2 => "decimal"
        |""".stripMargin
    val file     = physical("MatchScalarEdit.scala", source)
    val original = descendants[ScLiteralPatternImpl](file).head
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(original)
    assertEquals("2.5", pointer.getElement.getText)
    assertEquals(
      "2.5",
      PsiTreeUtil.findChildOfType(file.copy(), classOf[ScLiteralPatternImpl]).getText
    )
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(source.indexOf("dec"), source.indexOf("dec") + 3, "decimal")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    var literals = descendants[ScLiteralPatternImpl](file)
    assertEquals(1, literals.size)
    assertEquals("2.5", literals.head.getText)
    assertEquals("2.5", pointer.getElement.getText)
    assertEquals(
      "2.5",
      PsiTreeUtil.findChildOfType(file.copy(), classOf[ScLiteralPatternImpl]).getText
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, edited)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    literals = descendants[ScLiteralPatternImpl](file)
    assertEquals(1, literals.size)
    assertEquals("-4.5e2", literals.head.getText)
    assertEquals("-4.5e2", pointer.getElement.getText)
    assertEquals(
      "-4.5e2",
      PsiTreeUtil.findChildOfType(file.copy(), classOf[ScLiteralPatternImpl]).getText
    )

  @Test
  def testAppliedTypedPatternsProduceNativePsi(): Unit =
    val source        =
      """def applied(x: Any): Any = x match
        |  case y: List[Int] => "a"
        |  case y: Either[String, Int] => "b"
        |  case y: Outer[Middle[Inner]] => "c"
        |  case Some(y: Map[Int, String]) => "d"
        |  case (y: Option[Int], z) => "e"
        |  case w: List[Option[Either[Int, String]]] => "f"
        |  case _: Option[Int] | _: List[Int] => "alt"
        |""".stripMargin
    val file          = physical("MatchAppliedTypes.scala", source)
    assertEquals(7, descendants[ScCaseClauseImpl](file).size)
    val typed         = descendants[Sc3TypedPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(8, typed.size)
    val parameterized = descendants[ScParameterizedTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(11, parameterized.size)
    assertEquals(
      Vector(
        "List[Int]",
        "Either[String, Int]",
        "Outer[Middle[Inner]]",
        "Middle[Inner]",
        "Map[Int, String]",
        "Option[Int]",
        "List[Option[Either[Int, String]]]",
        "Option[Either[Int, String]]",
        "Either[Int, String]",
        "Option[Int]",
        "List[Int]"
      ),
      parameterized.map(_.getText)
    )
    assertEquals(11, descendants[ScTypeArgsImpl](file).size)
    val matchPsi      = descendants[ScMatchImpl](file).head
    assertEquals(
      22,
      PsiTreeUtil.findChildrenOfType(matchPsi, classOf[ScSimpleTypeElementImpl]).asScala.toVector.size
    )
    parameterized.foreach: applied =>
      val args = applied.typeArgList
      assertEquals(applied.getTextRange.getEndOffset, args.getTextRange.getEndOffset)
      assertTrue(args.isInstanceOf[ScTypeArgs])
      applied.getParent match
        case wrapper: org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern =>
          assertEquals(applied.getTextRange, wrapper.getTextRange)
        case nestedArgs: ScTypeArgs                                                        =>
          assertEquals(applied.getTextRange.getStartOffset - 1, nestedArgs.getTextRange.getStartOffset)
          assertEquals(applied.getTextRange.getEndOffset + 1, nestedArgs.getTextRange.getEndOffset)
        case other                                                                         =>
          fail(s"unexpected applied-type parent: ${other.getClass.getName}")
    val rootApplied   = parameterized.head
    assertEquals("List[Int]", rootApplied.getText)
    assertEquals("List", rootApplied.typeElement.getText)
    assertEquals(1, rootApplied.typeArgList.typeArgs.size)
    val multiArg      = parameterized(1)
    assertEquals(2, multiArg.typeArgList.typeArgs.size)
    val nested        = parameterized(3)
    assertEquals("Middle[Inner]", nested.getText)
    assertTrue(nested.typeElement.isInstanceOf[ScSimpleTypeElementImpl])
    val deep          = parameterized(6)
    assertEquals("List", deep.typeElement.getText)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)
    val stubClasses   = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.toVector.map(_.getClass.getName)
    assertFalse(stubClasses.exists(name => name.contains("Type") || name.contains("Pattern")))

  @Test
  def testAppliedTypedPatternsHandleDeepAndWideTypes(): Unit =
    val source        =
      """def edges(x: Any): Any = x match
        |  case y: L2[L3[L4[L5[L6[L7[L8[L9[L10[L11[L12[L13[L14[L15[L16[Inner]]]]]]]]]]]]]]] => "deep"
        |  case y: W1[W2, W3, W4, W5, W6, W7, W8, W9, W10, W11, W12, W13, W14, W15, W16, W17, W18, W19, W20, W21, W22, W23, W24, W25, W26, W27, W28, W29, W30, W31, W32, Inner] => "wide"
        |  case y: F[A][B] => "curried"
        |""".stripMargin
    val file          = physical("MatchAppliedEdges.scala", source)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)
    val parameterized = descendants[ScParameterizedTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(18, parameterized.size)
    assertEquals(
      "L2[L3[L4[L5[L6[L7[L8[L9[L10[L11[L12[L13[L14[L15[L16[Inner]]]]]]]]]]]]]]]",
      parameterized.head.getText
    )
    assertTrue(
      s"parameterized texts: ${parameterized.map(_.getText).mkString(",")}",
      parameterized.exists(_.getText == "F[A][B]")
    )
    val curried       = parameterized.find(_.getText == "F[A][B]").get
    assertTrue(curried.typeElement.isInstanceOf[ScParameterizedTypeElementImpl])
    assertEquals("F[A]", curried.typeElement.getText)
    assertEquals(1, curried.typeArgList.typeArgs.size)
    assertTrue(parameterized.exists(_.getText == "F[A]"))
    var depth         = 1
    var current       = parameterized.head
    while current.typeArgList.typeArgs.head.isInstanceOf[ScParameterizedTypeElementImpl] do
      depth += 1
      current = current.typeArgList.typeArgs.head.asInstanceOf[ScParameterizedTypeElementImpl]
    assertEquals(15, depth)
    val wide          = parameterized(15)
    assertEquals(
      "W1[W2, W3, W4, W5, W6, W7, W8, W9, W10, W11, W12, W13, W14, W15, W16, W17, W18, W19, W20, W21, W22, W23, W24, W25, W26, W27, W28, W29, W30, W31, W32, Inner]",
      wide.getText
    )
    assertEquals(32, wide.typeArgList.typeArgs.size)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)

  @Test
  def testAppliedTypedPatternsTolerateTriviaAndSurviveLifecycle(): Unit =
    val source    =
      """def trivia(x: Any): Any = x match
        |  case y: Either[
        |    Int, // first
        |    String
        |  ] => "trivia"
        |""".stripMargin
    val file      = physical("MatchAppliedTrivia.scala", source)
    val typed     = descendants[Sc3TypedPatternImpl](file)
    assertEquals(1, typed.size)
    val applied   = descendants[ScParameterizedTypeElementImpl](file)
    assertEquals(1, applied.size)
    assertEquals("Either", applied.head.typeElement.getText)
    val argTexts  = applied.head.typeArgList.typeArgs.map(_.getText)
    assertEquals(Vector("Int", "String"), argTexts)
    val original  = typed.head
    val pointer   = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(original)
    assertEquals(
      applied.head.getText,
      PsiTreeUtil.findChildOfType(file.copy(), classOf[ScParameterizedTypeElementImpl]).getText
    )
    val document  = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, source.replace("// first", ""))
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val afterEdit = descendants[ScParameterizedTypeElementImpl](file)
    assertEquals(1, afterEdit.size)
    assertEquals(Vector("Int", "String"), afterEdit.head.typeArgList.typeArgs.map(_.getText))
    assertEquals("Either[Int,String]", afterEdit.head.getText.replaceAll("\\s+", ""))
    assertEquals(
      "y:Either[Int,String]",
      pointer.getElement.getText.replaceAll("\\s+", "")
    )

  @Test
  def testAppliedTypedPatternEditTransitionsBetweenNativeAndFailClosed(): Unit =
    val supported                              =
      """def transitions(x: Any): Any = x match
        |  case y: List[Int] => "dec"
        |""".stripMargin
    val file                                   = physical("MatchAppliedTransition.scala", supported)
    assertEquals(1, descendants[ScParameterizedTypeElementImpl](file).size)
    val document                               = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replaceType(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, supported.replace("List[Int]", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replaceType("List[_]")
    assertTrue(descendants[ScParameterizedTypeElementImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replaceType("List[Int]")
    assertEquals(1, descendants[ScParameterizedTypeElementImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)

  @Test
  def testAppliedTypedPatternsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    val source      =
      """package applied
        |def applied(x: Any): Any = x match
        |  case y: List[Int] => "dec"
        |  case y: Either[String, Int] => "b"
        |""".stripMargin
    val file        = physical("MatchAppliedPersistence.scala", source).asInstanceOf[PsiFileImpl]
    val document    = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val shape       = stubShape(stubs)
    assertFalse(shape.exists(name => name.contains("Type") || name.contains("Pattern")))
    val beforeIndex = indexShape(stubs)
    val output      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes       = output.toByteArray
    val restored    = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(shape, stubShape(restored.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala.toVector))
    val repeated    = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, source)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    val literals    = descendants[ScParameterizedTypeElementImpl](file).map(_.getText)
    assertEquals(Vector("List[Int]", "Either[String, Int]"), literals)
    literals.foreach: text =>
      val element = descendants[ScParameterizedTypeElementImpl](file).find(_.getText == text).get
      assertSame(element, element.getNavigationElement)

  @Test
  def testAppliedTypePatternsWithCoveredGuardAndBodyStayFullyNative(): Unit =
    val source        =
      """def guardBody(x: Any): Any = x match
        |  case v if v == 0 => v
        |  case y: List[Int] => y
        |  case y: Either[String, Int] => y
        |  case _ => 0
        |""".stripMargin
    val file          = physical("MatchAppliedGuardBody.scala", source)
    val capability    = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(4, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScGuard](file).size)
    val parameterized = descendants[ScParameterizedTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(2, parameterized.size)
    assertEquals(Vector("List[Int]", "Either[String, Int]"), parameterized.map(_.getText))
    val guards        = descendants[ScGuard](file)
    assertEquals(1, guards.size)
    assertTrue(guards.head.getText.contains("v == 0"))
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testUnsupportedPatternShapesFailClosedAtFileScope(): Unit =
    val sources = Vector(
      """def pending(x: Any): Any = x match
        |  case s"lit" => "interp"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: a.B => "qualified"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: List[_] => "wildcard"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: (Int) => "paren"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: (Int => Int) => "fn"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: Any { type X } => "refine"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: Int @unchecked => "annot"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: (Int & String) => "intersect"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: (Int | String) => "union"
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val pending = myFixture.addFileToProject(s"src/MatchUnsupported$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported patterns should cause capability failure (source $index)", failure.isDefined)
      assertTrue(
        s"failure should mention uncovered shape: ${failure.get}",
        failure.get.toString.contains("UncoveredCompilerShape")
      )
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
      assertTrue(descendants[ScLiteralPatternImpl](file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScParameterizedTypeElementImpl](file).isEmpty)
      assertTrue(descendants[ScTypeArgsImpl](file).isEmpty)
    }

  @Test
  def testScalarLiteralPatternEditsTransitionBetweenNativeAndFailClosed(): Unit =
    val template                                  =
      """def transitions(x: Any): Any = x match
        |  case LITERAL => "dec"
        |""".stripMargin
    val supported                                 = template.replace("LITERAL", "2.5")
    val file                                      = physical("MatchScalarTransition.scala", supported)
    assertEquals(Vector("2.5"), descendants[ScLiteralPatternImpl](file).map(_.getText))
    val document                                  = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replaceLiteral(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("LITERAL", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replaceLiteral("9_223L")
    assertEquals(Vector("9_223L"), descendants[ScLiteralPatternImpl](file).map(_.getText))
    assertEquals(1, descendants[ScMatchImpl](file).size)
    replaceLiteral("s\"lit\"")
    assertTrue(descendants[ScLiteralPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    replaceLiteral("\"text\"")
    assertEquals(Vector("\"text\""), descendants[ScLiteralPatternImpl](file).map(_.getText))
    assertEquals(1, descendants[ScMatchImpl](file).size)
    replaceLiteral("2.5.")
    assertTrue(descendants[ScLiteralPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    replaceLiteral("2.5")
    assertEquals(Vector("2.5"), descendants[ScLiteralPatternImpl](file).map(_.getText))
    assertEquals(1, descendants[ScMatchImpl](file).size)

  @Test
  def testLongLiteralPatternsLowerToNativePsiAcrossTheExactLexicalMatrix(): Unit =
    val source     =
      """def longs(x: Any): Any = x match
        |  case 5L => "dec"
        |  case 5l => "lower"
        |  case 0xFFL => "hexU"
        |  case 0XffL => "hexu"
        |  case 0b101L => "bin"
        |  case 1_0_0L => "under"
        |  case 9_223_372_036_854_775_807L => "max"
        |  case -9_223_372_036_854_775_808L => "min"
        |  case -9_223L => "neg"
        |  case -0L => "negzero"
        |  case _ => 0
        |""".stripMargin
    val file       = physical("MatchLongLiterals.scala", source)
    val capability = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(11, descendants[ScCaseClauseImpl](file).size)
    val literals   = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(10, literals.size)
    assertEquals(
      Vector(
        "5L",
        "5l",
        "0xFFL",
        "0XffL",
        "0b101L",
        "1_0_0L",
        "9_223_372_036_854_775_807L",
        "-9_223_372_036_854_775_808L",
        "-9_223L",
        "-0L"
      ),
      literals.map(_.getText)
    )
    literals.foreach: literal =>
      assertTrue(literal.getText, literal.getLiteral.isInstanceOf[ScLongLiteral])
      assertEquals(literal.getLiteral, literal.getChildren.head)
      assertEquals(literal.getTextRange, literal.getLiteral.getTextRange)
    val byText     = literals.map(literal => literal.getText -> literal).toMap
    assertEquals(java.lang.Long.valueOf(5L), byText("5L").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(5L), byText("5l").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(255L), byText("0xFFL").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(255L), byText("0XffL").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(5L), byText("0b101L").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(100L), byText("1_0_0L").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(9223372036854775807L), byText("9_223_372_036_854_775_807L").getLiteral.getValue)
    assertEquals(
      java.lang.Long.valueOf(-9223372036854775808L),
      byText("-9_223_372_036_854_775_808L").getLiteral.getValue
    )
    assertEquals(java.lang.Long.valueOf(-9223L), byText("-9_223L").getLiteral.getValue)
    assertEquals(java.lang.Long.valueOf(0L), byText("-0L").getLiteral.getValue)
    assertEquals("9223372036854775807L", byText("9_223_372_036_854_775_807L").getLiteral.literalType.canonicalText)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testNullLiteralPatternLowersToNativeNullLiteralPsi(): Unit =
    val source      =
      """def nulls(x: Any): Any = x match
        |  case null => "none"
        |  case _ => 0
        |""".stripMargin
    val file        = physical("MatchNullLiteral.scala", source)
    val capability  = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(2, descendants[ScCaseClauseImpl](file).size)
    val literals    = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("null"), literals.map(_.getText))
    val nullPattern = literals.head
    assertTrue(nullPattern.getLiteral.isInstanceOf[ScNullLiteral])
    assertEquals(nullPattern.getLiteral, nullPattern.getChildren.head)
    assertEquals(nullPattern.getTextRange, nullPattern.getLiteral.getTextRange)
    assertNull(nullPattern.getLiteral.getValue)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testUnitTuplePatternLowersToEmptyNativeTuplePsi(): Unit =
    val source     =
      """def units(x: Any): Any = x match
        |  case () => "unit"
        |  case ( ) => "spaced"
        |  case (/*c*/) => "commented"
        |  case _ => 0
        |""".stripMargin
    val file       = physical("MatchUnitTuple.scala", source)
    val capability = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(4, descendants[ScCaseClauseImpl](file).size)
    val tuples     = descendants[ScTuplePatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(3, tuples.size)
    assertEquals(Vector("()", "( )", "(/*c*/)"), tuples.map(_.getText))
    tuples.foreach: tuple =>
      assertEquals(None, tuple.patternList)
      assertEquals(Vector.empty, tuple.subpatterns)
    assertTrue(descendants[ScLiteralPatternImpl](file).isEmpty)
    assertTrue(descendants[ScUnitExpr](file).isEmpty)
    assertTrue(descendants[ScCompositePatternImpl](file).isEmpty)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testConstantPatternsNestThroughExistingPatternEdgesAndCoexistWithGuardsAndBodies(): Unit =
    val source       =
      """def nested(c: C2, x: Any, n: Int): Any = x match
        |  case (5L, null, ()) => "tuple"
        |  case 5L | null | () => "alt"
        |  case C2(5L, null) => "ctor"
        |  case w @ 5L => "named"
        |  case 9_223L if n > 0 => "guarded"
        |  case _ => 0
        |""".stripMargin
    val file         = physical("MatchConstantNesting.scala", source)
    val capability   = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(6, descendants[ScCaseClauseImpl](file).size)
    val literals     = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector("5L", "null", "5L", "null", "5L", "null", "5L", "9_223L"),
      literals.map(_.getText)
    )
    literals.foreach: literal =>
      assertTrue(
        literal.getText,
        literal.getLiteral.isInstanceOf[ScLongLiteral] || literal.getLiteral.isInstanceOf[ScNullLiteral]
      )
    val tuples       = descendants[ScTuplePatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("(5L, null, ())", "()", "()"), tuples.map(_.getText))
    assertEquals(3, tuples.head.subpatterns.size)
    assertEquals(None, tuples(1).patternList)
    assertEquals(None, tuples(2).patternList)
    assertEquals(1, descendants[ScNamingPatternImpl](file).size)
    assertEquals(1, descendants[ScGuard](file).size)
    assertEquals(1, descendants[ScConstructorPatternImpl](file).size)
    val nestedTuples = descendants[ScTuplePatternImpl](file).filter(_.subpatterns.nonEmpty)
    nestedTuples.foreach: tuple =>
      val givenChildren = tuple.subpatterns.collect { case g: ScGivenPatternImpl => g }
      givenChildren.foreach: givenPattern =>
        val kwLeaf = givenPattern.getNode.getChildren(null).find(_.getText == "given").orNull
        assertNotNull(kwLeaf)
        assertEquals(5, kwLeaf.getTextRange.getLength)
        assertEquals(givenPattern.getTextRange.getStartOffset, kwLeaf.getTextRange.getStartOffset)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testUnitTuplePatternEditsTransitionBetweenNativeAndFailClosed(): Unit =
    val template                                =
      """def transitions(x: Any): Any = x match
        |  case PAREN => "u"
        |""".stripMargin
    val supported                               = template.replace("PAREN", "()")
    val file                                    = physical("MatchUnitTransition.scala", supported)
    assertEquals(Vector("()"), descendants[ScTuplePatternImpl](file).map(_.getText))
    val document                                = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replaceParen(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("PAREN", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replaceParen("(1)")
    assertEquals(Vector("(1)"), descendants[ScParenthesisedPatternImpl](file).map(_.getText))
    assertEquals(Vector("1"), descendants[ScLiteralPatternImpl](file).map(_.getText))
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertTrue(descendants[ScTuplePatternImpl](file).isEmpty)
    replaceParen("( )")
    assertEquals(Vector("( )"), descendants[ScTuplePatternImpl](file).map(_.getText))
    assertEquals(1, descendants[ScMatchImpl](file).size)
    replaceParen("(2, 3)")
    assertEquals(Vector("(2, 3)"), descendants[ScTuplePatternImpl](file).map(_.getText))
    assertEquals(2, descendants[ScTuplePatternImpl](file).head.subpatterns.size)
    replaceParen("()")
    assertEquals(Vector("()"), descendants[ScTuplePatternImpl](file).map(_.getText))
    assertEquals(None, descendants[ScTuplePatternImpl](file).head.patternList)

  @Test
  def testGivenPatternsLowerToNativePsiAcrossSimpleAndAppliedTypes(): Unit =
    val source        =
      """def givens1(x: Any): Any = x match
        |  case given T => "simple"
        |
        |def givens2(y: Any): Any = y match
        |  case given Ordering[Int] => "applied"
        |
        |def givens3(z: Any): Any = z match
        |  case given Ordering[Outer[Middle[Inner]]] => "deep"
        |""".stripMargin
    val file          = physical("MatchGivenPatterns.scala", source)
    val capability    = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(3, descendants[ScMatchImpl](file).size)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)
    val givenPatterns = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector("given T", "given Ordering[Int]", "given Ordering[Outer[Middle[Inner]]]"),
      givenPatterns.map(_.getText)
    )
    assertEquals(Vector("given_T", "given_Ordering_Int", "given_Ordering_Outer"), givenPatterns.map(_.name))
    givenPatterns.foreach: givenPattern =>
      assertTrue(givenPattern.isInstanceOf[ScGivenPattern])
      val typeElement  = givenPattern.typeElement
      assertNotNull(typeElement)
      assertSame(typeElement, givenPattern.nameId)
      assertFalse(givenPattern.isWildcard)
      assertEquals(givenPattern.getTextRange.getEndOffset, typeElement.getTextRange.getEndOffset)
      assertEquals(givenPattern.getText, givenPattern.getTextRange.substring(file.getText))
      val astChildren  = givenPattern.getNode.getChildren(null).toVector
      val keywordLeaf  = astChildren.find(child => child.getText == "given").orNull
      assertNotNull("direct GivenKeyword leaf with exact text 'given' is missing", keywordLeaf)
      assertEquals(org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType.GivenKeyword, keywordLeaf.getElementType)
      assertEquals(5, keywordLeaf.getTextRange.getLength)
      assertEquals(givenPattern.getTextRange.getStartOffset, keywordLeaf.getTextRange.getStartOffset)
      val typeChildren = astChildren.filter(child => child.getPsi.isInstanceOf[ScTypeElement])
      assertEquals(1, typeChildren.size)
      assertSame(typeElement, typeChildren.head.getPsi)
      assertTrue(astChildren.exists(child => child.getElementType.toString == "WHITE_SPACE"))
      assertTrue(PsiTreeUtil.findChildrenOfType(givenPattern, classOf[ScPatterns]).isEmpty)
      assertTrue(PsiTreeUtil.findChildOfType(givenPattern, classOf[ScModifierList]) == null)
      assertTrue(givenPattern.name != null && givenPattern.name.nonEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[ScParameterizedTypeElementImpl](file).size >= 2)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testNamedGivenPatternsLowerToNamingWrapperContainingGivenPattern(): Unit =
    val source         =
      """def namedGivens1(x: Any): Any = x match
        |  case ord @ given Ordering[Int] => "named"
        |
        |def namedGivens2(y: Any): Any = y match
        |  case _ @ given T => "wildcardBinder"
        |
        |def namedGivens3(z: Any): Any = z match
        |  case `back-tick` @ given T => "backticked"
        |""".stripMargin
    val file           = physical("MatchNamedGivenPatterns.scala", source)
    val capability     = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(3, descendants[ScMatchImpl](file).size)
    val namingPatterns = descendants[ScNamingPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector("ord @ given Ordering[Int]", "_ @ given T", "`back-tick` @ given T"),
      namingPatterns.map(_.getText)
    )
    val givenPatterns  = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("given Ordering[Int]", "given T", "given T"), givenPatterns.map(_.getText))
    namingPatterns.zip(givenPatterns).foreach { case (naming, givenPattern) =>
      assertSame(givenPattern, naming.named)
      assertTrue(naming.getTextRange.contains(givenPattern.getTextRange))
      assertFalse(naming.nameId.getText, naming.nameId.getText.isEmpty)
    }
    assertEquals("`back-tick`", namingPatterns(2).nameId.getText)
    givenPatterns.foreach: givenPattern =>
      assertSame(givenPattern.typeElement, givenPattern.nameId)
      assertFalse(givenPattern.isWildcard)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testGivenPatternsNestThroughExistingEdgesAndCoexistWithGuards(): Unit =
    val source        =
      """def nested(c: C3, x: Any, n: Int): Any = x match
        |  case (given T, given Ordering[Int]) => "tuple"
        |  case given T | given Ordering[Int] => "alt"
        |  case C3(given T) => "ctor"
        |  case w @ given T if n > 0 => "guarded"
        |  case _ => 0
        |""".stripMargin
    val file          = physical("MatchGivenNesting.scala", source)
    val capability    = Scala3SyntaxCapabilityService.get(getProject)
    assertTrue(capability.failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source)).isEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(5, descendants[ScCaseClauseImpl](file).size)
    val givenPatterns = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector("given T", "given Ordering[Int]", "given T", "given Ordering[Int]", "given T", "given T"),
      givenPatterns.map(_.getText)
    )
    assertEquals(1, descendants[ScGuard](file).size)
    assertEquals(1, descendants[ScConstructorPatternImpl](file).size)
    val tuples        = descendants[ScTuplePatternImpl](file)
    assertEquals(1, tuples.size)
    assertEquals(2, tuples.head.subpatterns.size)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)
    payloadsOutsideMatch(file)

  @Test
  def testGivenPatternShapesStayFailClosedOrRemainReferencePatterns(): Unit =
    val sources = Vector(
      """def pending(x: Any): Any = x match
        |  case given a.B => "qualified"
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val pending = myFixture.addFileToProject(s"src/MatchGivenUnsupported$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported given shape should fail closed (source $index): ${failure}", failure.isDefined)
      assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
    }
  @Test
  def testGivenPatternEditsTransitionBetweenNativeAndFailClosed(): Unit     =
    val template                                =
      """def transitions(x: Any): Any = x match
        |  case GIVEN => "g"
        |""".stripMargin
    val supported                               = template.replace("GIVEN", "given T")
    val file                                    = physical("MatchGivenTransition.scala", supported)
    assertEquals(Vector("given T"), descendants[ScGivenPatternImpl](file).map(_.getText))
    val document                                = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replaceGiven(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("GIVEN", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replaceGiven("given a.B")
    assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    replaceGiven("ord @ given Ordering[Int]")
    assertEquals(1, descendants[ScNamingPatternImpl](file).size)
    assertEquals(Vector("given Ordering[Int]"), descendants[ScGivenPatternImpl](file).map(_.getText))
    replaceGiven("given T")
    assertEquals(Vector("given T"), descendants[ScGivenPatternImpl](file).map(_.getText))
    assertEquals(1, descendants[ScMatchImpl](file).size)

  @Test
  def testGivenPatternsOutsideMatchOwnershipStayFailClosed(): Unit =
    val sources = Vector(
      """def partial(x: Any): PartialFunction[Any, Any] = { case given T => "pf" }""",
      """def generator(xs: List[Any]): List[Any] = for given T <- xs yield givenCheck""",
      """def quoted(x: Any): Any = '{ case given T => "q" }""",
      "given T = new T",
      """def catcher(x: Any): Any = try x catch { case given T => "catch" }"""
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val pending = myFixture.addFileToProject(s"src/MatchGivenContext$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      // Forces the deferred PSI parse before the capability failure is read.
      val _       = descendants[ScGivenPatternImpl](file)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"excluded context must fail closed (source $index)", failure.isDefined)
      assertTrue(
        s"whole-file failure must leave no partial native pattern PSI (source $index)",
        descendants[ScGivenPatternImpl](file).isEmpty && descendants[ScNamingPatternImpl](file).isEmpty &&
          descendants[Sc3TypedPatternImpl](file).isEmpty && descendants[ScMatchImpl](file).isEmpty
      )
    }

  @Test
  def testParenthesizedPatternsOwnSingleParensAcrossTheShapeMatrix(): Unit =
    val source =
      """def parens(x: Any): Any = x match
        |  case (y) => "ident"
        |  case (_) => "wildcard"
        |  case (1) => "literal"
        |  case (Nil) => "stable"
        |  case (t: String) => "typed"
        |  case (Some(inner)) => "constructor"
        |  case (named @ Some(renamed)) => "naming"
        |  case (1 | 2) => "alternative"
        |  case (given Ordering[Int]) => "given"
        |  case ((deep)) => "nested"
        |  case ((a, b)) => "tupleInner"
        |  case (()) => "unitInner"
        |  case other => "fallback"
        |""".stripMargin
    val file   = physical("MatchParenthesizedMatrix.scala", source)
    val parens = descendants[ScParenthesisedPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(13, parens.size)
    assertEquals(13, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals("(y)", parens.head.getText)
    assertEquals("y", parens.head.innerElement.toVector.map(_.getText).mkString)
    assertEquals(Vector("((deep))", "(deep)"), parens.collect { case p if p.getText.contains("deep") => p.getText })
    assertEquals(1, descendants[ScGivenPatternImpl](file).size)
    assertEquals(2, descendants[ScTuplePatternImpl](file).size)

    // Exact ranges, delimiter tokens, direct children, and nested sameTreeParent linkage.
    val firstWrapper     = source.indexOf("(y)")
    assertEquals(firstWrapper, parens.head.getTextRange.getStartOffset)
    assertEquals(firstWrapper + 3, parens.head.getTextRange.getEndOffset)
    val wrapperChildren  = parens.head.getNode.getChildren(null).toVector.map(_.getText)
    assertEquals(Vector("(", "y", ")"), wrapperChildren)
    val deepPair         = parens.collect { case p if p.getText.contains("deep") => p }
    assertEquals(source.indexOf("((deep))"), deepPair.head.getTextRange.getStartOffset)
    assertEquals(source.indexOf("((deep))") + 8, deepPair.head.getTextRange.getEndOffset)
    assertEquals(deepPair.last, deepPair.head.innerElement.get)
    assertSame(deepPair.head, deepPair.last.sameTreeParent.get)
    // The delimiter leaves are token leaves directly under the wrapper, with no synthetic text.
    val wrapperNodes     = parens.head.getNode.getChildren(null).toVector
    val wrapperLeafTexts = wrapperNodes.collect { case leaf if leaf.getFirstChildNode == null => leaf.getText }
    assertEquals(Vector("("), wrapperLeafTexts.take(1))
    assertEquals(Vector(")"), wrapperLeafTexts.takeRight(1))

    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertEquals(None, failure)

  @Test
  def testParenthesizedPatternDisambiguationStaysCompilerShapeAuthoritative(): Unit =
    val owned         = physical(
      "MatchParensDisambiguation.scala",
      """def disambiguation(x: Any): Any = x match
        |  case (a, b) => "tuple"
        |  case (p) => "parens"
        |  case other => "fallback"
        |""".stripMargin
    )
    assertEquals(Vector("(a, b)"), descendants[ScTuplePatternImpl](owned).map(_.getText))
    assertEquals(Vector("(p)"), descendants[ScParenthesisedPatternImpl](owned).map(_.getText))
    val closedSource  = """def trailing(x: Any): Any = x match
      |  case (y,) => "comma"
      |  case other => "fallback"
      |""".stripMargin
    val closedPending = myFixture.addFileToProject("src/MatchParensTrailingComma.scala", closedSource)
    val closedFile    = PsiManager.getInstance(getProject).findFile(closedPending.getVirtualFile)
    assertEquals(closedSource, closedFile.getText)
    // Forces the deferred PSI parse before the capability failure is read.
    val _             = descendants[ScParenthesisedPatternImpl](closedFile)
    val failure       = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(closedPending.getVirtualFile, ParserSyntaxSnapshot.digest(closedSource))
    assertTrue("trailing comma pattern must fail closed", failure.isDefined)

  @Test
  def testMissingCloseParenthesizedPatternsRecoverWithOwnedDiagnostic(): Unit =
    val source  = """def m(x: Any): Any = x match
      |  case (y => 1
      |  case _ => 2
      |""".stripMargin
    val pending = myFixture.addFileToProject("src/MatchParensMissingClose.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    // Forces the deferred PSI parse before the capability failure is read.
    val _       = descendants[ScParenthesisedPatternImpl](file)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertEquals("recovery file must be admitted", None, failure)
    val parens  = descendants[ScParenthesisedPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("(y "), parens.map(_.getText))
    assertEquals(Vector("y"), parens.head.innerElement.toVector.map(_.getText))
    // sameTreeParent of the wrapper pattern resolves to no enclosing pattern in this fixture.
    assertEquals(None, parens.head.sameTreeParent)
    val errors  = descendants[PsiErrorElement](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(1, errors.size)
    assertEquals("missing-close-before-case-arrow", errors.head.getErrorDescription)
    assertTrue("the recovery error must be zero-width", errors.head.getTextRange.isEmpty)
    // The zero-width recovery error sits immediately after the inner pattern content; the wrapper
    // itself extends through the retained trivia up to the case arrow, which stays outside it.
    assertEquals(
      "the recovery error must sit immediately after the retained inner pattern",
      parens.head.innerElement.get.getTextRange.getEndOffset,
      errors.head.getTextRange.getStartOffset
    )
    assertTrue(errors.head.getTextRange.isEmpty)
    assertEquals(source.indexOf("=>"), parens.head.getTextRange.getEndOffset)
    // dotc recovery absorbs the trailing clauses into the retained wrapper's span.
    assertEquals(1, descendants[ScCaseClauseImpl](file).size)
    assertTrue(descendants[ScTuplePatternImpl](file).isEmpty)

    // The wrapper children are the open delimiter, the inner pattern, and the owned zero-width
    // recovery error; no synthetic close delimiter is emitted anywhere in the file.
    val childrenTexts = parens.head.getNode.getChildren(null).toVector.map(_.getText)
    assertTrue("no synthetic close delimiter may be emitted", !childrenTexts.contains(")"))
    assertSame(errors.head.getParent, parens.head)
    val arrowOffset   = source.indexOf("=>")
    val arrowLeaf     = file.findElementAt(arrowOffset)
    assertNotNull(arrowLeaf)
    assertEquals("=>", arrowLeaf.getText)
    assertTrue(
      "the case arrow must remain outside the recovered wrapper",
      arrowLeaf.getTextRange.getStartOffset >= parens.head.getTextRange.getEndOffset
    )

  @Test
  def testParenthesizedPatternsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    val source        =
      """package parens
        |def parenPat(x: Any): Any = x match
        |  case (y) => "simple"
        |  case (p: String) => "typed"
        |""".stripMargin
    val file          = physical("MatchParenthesizedPersistence.scala", source).asInstanceOf[PsiFileImpl]
    val document      = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val tree          = file.calcStubTree
    val stubs         = tree.getPlainList.asScala.toVector
    val shape         = stubShape(stubs)
    assertFalse(shape.exists(name => name.contains("Parenthesised") || name.contains("Pattern")))
    val beforeIndex   = indexShape(stubs)
    val output        = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes         = output.toByteArray
    val restored      = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(shape, stubShape(restored.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala.toVector))
    val repeated      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    val reloadedStubs = file.getStubTree.getPlainList.asScala.toVector
    assertEquals(shape, stubShape(reloadedStubs))
    assertEquals(beforeIndex, indexShape(reloadedStubs))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, source)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    file.setTreeElementPointer(null)

  @Test
  def testGivenPatternsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    val source        =
      """package givenpats
        |def givenPat(x: Any): Any = x match
        |  case given T => "simple"
        |  case ord @ given Ordering[Int] => "named"
        |""".stripMargin
    val file          = physical("MatchGivenPersistence.scala", source).asInstanceOf[PsiFileImpl]
    val document      = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val tree          = file.calcStubTree
    val stubs         = tree.getPlainList.asScala.toVector
    val shape         = stubShape(stubs)
    assertFalse(shape.exists(name => name.contains("Given") || name.contains("Pattern")))
    val beforeIndex   = indexShape(stubs)
    val output        = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes         = output.toByteArray
    val restored      = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(shape, stubShape(restored.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala.toVector))
    val repeated      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    val reloadedStubs = file.getStubTree.getPlainList.asScala.toVector
    assertEquals(shape, stubShape(reloadedStubs))
    assertEquals(beforeIndex, indexShape(reloadedStubs))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, source)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    val givenPatterns = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("given T", "given Ordering[Int]"), givenPatterns.map(_.getText))

  @Test
  def testScalarLiteralPatternsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    val source        =
      """package scalars
        |def scalar(x: Any): Any = x match
        |  case 2.5 => "dec"
        |  case 'c' => "chr"
        |""".stripMargin
    val file          = physical("MatchScalarPersistence.scala", source).asInstanceOf[PsiFileImpl]
    val document      = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val tree          = file.calcStubTree
    val stubs         = tree.getPlainList.asScala.toVector
    val shape         = stubShape(stubs)
    assertFalse(shape.exists(name => name.contains("Literal") || name.contains("Pattern")))
    val beforeIndex   = indexShape(stubs)
    val output        = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes         = output.toByteArray
    val restored      = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(shape, stubShape(restored.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala.toVector))
    val repeated      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    val reloadedStubs = file.getStubTree.getPlainList.asScala.toVector
    assertEquals(shape, stubShape(reloadedStubs))
    assertEquals(beforeIndex, indexShape(reloadedStubs))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, source)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    val literals      = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("2.5", "'c'"), literals.map(_.getText))
    literals.foreach: literal =>
      assertSame(literal, literal.getNavigationElement)
      assertSame(literal.getLiteral, literal.getLiteral.getNavigationElement)

  @Test
  def testConstantPatternsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    val source        =
      """package constants
        |def constant(x: Any): Any = x match
        |  case 5L => "long"
        |  case -9_223L => "neglong"
        |  case null => "null"
        |  case () => "unit"
        |""".stripMargin
    val file          = physical("MatchConstantPersistence.scala", source).asInstanceOf[PsiFileImpl]
    val document      = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val tree          = file.calcStubTree
    val stubs         = tree.getPlainList.asScala.toVector
    val shape         = stubShape(stubs)
    assertFalse(shape.exists(name => name.contains("Literal") || name.contains("Pattern") || name.contains("Tuple")))
    val beforeIndex   = indexShape(stubs)
    val output        = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes         = output.toByteArray
    val restored      = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(shape, stubShape(restored.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala.toVector))
    val repeated      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    val reloadedStubs = file.getStubTree.getPlainList.asScala.toVector
    assertEquals(shape, stubShape(reloadedStubs))
    assertEquals(beforeIndex, indexShape(reloadedStubs))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(0, document.getTextLength, source)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(shape, stubShape(file.getStubTree.getPlainList.asScala.toVector))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala.toVector))
    val literals      = descendants[ScLiteralPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("5L", "-9_223L", "null"), literals.map(_.getText))
    literals.foreach: literal =>
      assertSame(literal, literal.getNavigationElement)
      assertSame(literal.getLiteral, literal.getLiteral.getNavigationElement)

  private def indexShape(stubs: Iterable[com.intellij.psi.stubs.Stub]): Vector[String] =
    val result = Vector.newBuilder[String]
    val sink   = new com.intellij.psi.stubs.IndexSink:
      override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
          indexKey: com.intellij.psi.stubs.StubIndexKey[K, Psi],
          value: K
      ): Unit =
        result += s"${indexKey.toString}|${value.toString}"
    stubs.foreach(stub =>
      Option(stub.getStubSerializer).foreach(
        _.asInstanceOf[
          com.intellij.psi.stubs.ObjectStubSerializer[com.intellij.psi.stubs.Stub, com.intellij.psi.stubs.Stub]
        ]
          .indexStub(stub, sink)
      )
    )
    result.result()

  private def stubShape(stubs: Iterable[com.intellij.psi.stubs.Stub]): Vector[String] = stubs.iterator
    .flatMap(stub =>
      Option(stub.getStubSerializer).map(serializer => s"${stub.getClass.getName}|${serializer.getExternalId}")
    )
    .toVector

  @Test
  def testBinderSequenceMarkerProducesSeqWildcardPattern(): Unit =
    val source   =
      """def binder(x: Any): Any = x match
        |  case List(n @ _*) => n
        |""".stripMargin
    val file     = physical("MatchBinderMarker.scala", source)
    val matchPsi = descendants[ScMatchImpl](file)
    assertEquals(1, matchPsi.size)
    val naming   = descendants[ScNamingPatternImpl](file)
    assertEquals(1, naming.size)
    assertEquals("n", naming.head.nameId.getText)
    val seq      = descendants[ScSeqWildcardPatternImpl](file)
    assertEquals(1, seq.size)
    assertEquals(naming.head, seq.head.getParent)
    assertEquals("*", seq.head.getText)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)

  private def payloadsOutsideMatch(file: com.intellij.psi.PsiFile): Unit =
    val blocks = descendants[ScBlock](file)
    assertTrue(blocks.nonEmpty)
    blocks.foreach: block =>
      assertTrue(
        block.getText,
        block.getChildren.isEmpty || !block.getText.startsWith("case")
      )

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
  ScStringLiteral
}
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
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertNull, assertSame, assertTrue}
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
  def testUnsupportedPatternShapesFailClosedAtFileScope(): Unit =
    val sources = Vector(
      """def pending(x: Any): Any = x match
        |  case 9_223L => "long"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case v: List[Int] => v
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case null => "null"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case s"lit" => "interp"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case -9_223L => "neglong"
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
        "failure should mention uncovered shape",
        failure.get.toString.contains("UncoveredCompilerShape")
      )
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
      assertTrue(descendants[ScLiteralPatternImpl](file).isEmpty)
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

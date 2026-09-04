package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{IndexSink, ObjectStubSerializer, Stub, StubIndexKey}
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScInfixTypeElement, ScParenthesisedTypeElement}
import org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScInfixTypeElementImpl
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternInfixTypePsiTest extends Scala3CompatTestCase:
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

  private def infixTypes(file: PsiFile): Vector[ScInfixTypeElementImpl] =
    descendants[ScInfixTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)

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

  private def assertInfixContract(
      infix: ScInfixTypeElement,
      operator: String,
      leftText: String,
      rightText: String
  ): Unit =
    assertTrue(infix.isInstanceOf[ScInfixTypeElementImpl])
    assertSame(infix, infix.getNavigationElement)
    assertEquals(leftText, infix.left.getText)
    assertEquals(operator, infix.operation.getText)
    assertEquals(Some(rightText), infix.rightOption.map(_.getText))
    val operation    = infix.operation.asInstanceOf[ScStableCodeReferenceImpl]
    assertSame(infix, operation.getParent)
    assertEquals(operator, operation.nameId.getText)
    assertSame(operation, operation.nameId.getParent)
    assertSame(operation, operation.getNavigationElement)
    assertEquals(operator, operation.qualName)
    assertEquals(None, operation.qualifier)
    assertSame(operation.getParent, operation.getContext)
    assertPhysicalContract(infix, infix.getContainingFile.getText)
    var visitedInfix = false
    val visitor      = new ScalaElementVisitor:
      override def visitInfixTypeElement(value: ScInfixTypeElement): Unit = visitedInfix = true
    infix.accept(visitor)
    assertTrue(visitedInfix)

  @Test
  def testMatchOwnedInfixTypesProduceNativePsiAcrossOperatorsAndPrecedence(): Unit =
    val source  =
      """def shapes(x: Any): Any = x match
        |  case y: (A | B) => "union"
        |  case y: (A & B) => "intersection"
        |  case y: (A | B | C) => "union-chain"
        |  case y: (A & B & C) => "intersection-chain"
        |  case y: (A | B & C) => "precedence"
        |  case y: ((A | B) & C) => "grouped"
        |  case y: (A
        |    | B) => "newline"
        |  case y: (A /* c1 */ | /* c2 */ B) => "trivia"
        |""".stripMargin
    val file    = physical("MatchInfixTypes1.scala", source)
    assertEquals(8, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(8, descendants[Sc3TypedPatternImpl](file).size)
    val wrapped = infixTypes(file)
    assertEquals(
      Vector(
        "A | B",
        "A & B",
        "A | B | C",
        "A | B",
        "A & B & C",
        "A & B",
        "A | B & C",
        "B & C",
        "(A | B) & C",
        "A | B",
        "A\n    | B",
        "A /* c1 */ | /* c2 */ B"
      ),
      wrapped.map(_.getText)
    )

    val first = wrapped.head
    assertEquals(source.indexOf("(A | B)"), first.getTextRange.getStartOffset - 1)
    first.getParent match
      case parens: ScParenthesisedTypeElement => assertEquals("(A | B)", parens.getText)
      case other                              => fail(s"unexpected infix parent: ${other.getClass.getName}")
    assertInfixContract(first, "|", "A", "B")

    // Union chains associate to the left: the outer infix's left is the previous infix.
    val chain = wrapped.find(_.getText == "A | B | C").get
    assertEquals("A | B", chain.left.getText)
    assertEquals("C", chain.rightOption.get.getText)
    assertSame(chain, chain.left.asInstanceOf[ScInfixTypeElement].getParent)
    assertInfixContract(chain.left.asInstanceOf[ScInfixTypeElement], "|", "A", "B")

    // & binds tighter than |: the mixed form nests the intersection on the right.
    val mixed = wrapped.find(_.getText == "A | B & C").get
    assertEquals("|", mixed.operation.getText)
    assertEquals("B & C", mixed.rightOption.get.getText)

    val grouped = wrapped.find(_.getText == "(A | B) & C").get
    grouped.left match
      case parens: ScParenthesisedTypeElement =>
        assertEquals("(A | B)", parens.getText)
        assertSame(grouped, parens.getParent)
      case other                              => fail(s"unexpected grouped left: ${other.getClass.getName}")

  @Test
  def testMatchOwnedInfixTypesOwnGivenPatternPositions(): Unit =
    val source  =
      """def givens(x: Any): Any = x match
        |  case given (A | B) => "anon"
        |  case n @ given (A & B) => "named"
        |  case _ @ given (A | B) => "wildcardBinder"
        |  case `bt` @ given (A | B) => "backtickedBinder"
        |""".stripMargin
    val file    = physical("MatchInfixTypesGiven.scala", source)
    val givens  = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(4, givens.size)
    val wrapped = infixTypes(file)
    assertEquals(Vector("A | B", "A & B", "A | B", "A | B"), wrapped.map(_.getText))
    assertEquals(4, descendants[ScCaseClauseImpl](file).size)
    wrapped.foreach { w =>
      assertSame(w, w.getNavigationElement)
      val parens       = w.getParent.asInstanceOf[ScParenthesisedTypeElement]
      assertEquals("ScParenthesisedTypeElementImpl", parens.getClass.getSimpleName)
      val givenPattern = PsiTreeUtil.getParentOfType(parens, classOf[ScGivenPatternImpl])
      assertNotNull(givenPattern)
      val operator     = if w.getText.contains("&") then "&" else "|"
      assertInfixContract(w, operator, w.left.getText, w.rightOption.get.getText)
    }

  @Test
  def testMatchOwnedInfixTypesComposeWithOwnedTypeFamilies(): Unit =
    val source  =
      """def composed(x: Any): Any = x match
        |  case y: Box[A | B] => "applied"
        |  case y: (A & B, C | D) => "tuple"
        |  case y: Box[? <: A | B] => "bounds"
        |  case y: Box[? >: A | B] => "lower-bound"
        |  case (x: A | B) => "pattern-and-type"
        |  case y: Box[A & stable.type | (C, D)] => "owned-mix"
        |  case y: (Unit | B) => "unit"
        |""".stripMargin
    val file    = physical("MatchInfixTypesComposed.scala", source)
    assertEquals(7, descendants[ScCaseClauseImpl](file).size)
    val wrapped = infixTypes(file)
    assertEquals(
      Vector(
        "A | B",
        "A & B",
        "C | D",
        "A | B",
        "A | B",
        "A & stable.type | (C, D)",
        "A & stable.type",
        "Unit | B"
      ),
      wrapped.map(_.getText)
    )
    wrapped.foreach(w => assertSame(w, w.getNavigationElement))

  @Test
  def testUnsupportedInfixShapesStayFailClosed(): Unit =
    val sources = Vector(
      """def customInfix(x: Any): Any = x match
        |  case y: (A :+: B) => 1
        |""".stripMargin,
      """def missingRight(x: Any): Any = x match
        |  case y: (A |) => 1
        |""".stripMargin,
      """def missingLeft(x: Any): Any = x match
        |  case y: (| A) => 1
        |""".stripMargin,
      """def missingOperator(x: Any): Any = x match
        |  case y: (A B) => 1
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchInfixUnsupported$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported infix shape should fail closed (source $index): $failure", failure.isDefined)
      assertTrue(infixTypes(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
      assertTrue(descendants[ScParenthesisedTypeElement](file).isEmpty)
      assertTrue(descendants[ScStableCodeReferenceImpl](file).isEmpty)
    }

  @Test
  def testMixedFileAdmittedAndUnsupportedInfixFailAtomically(): Unit =
    val source  =
      """def mixed(x: Any): Any = x match
        |  case y: (A | B) => "admitted"
        |  case y: (A :+: B) => "unsupported"
        |""".stripMargin
    val file    = pendingFile("MatchInfixMixedAtomicity.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"mixed infix files must fail the whole file closed: $failure", failure.isDefined)
    assertTrue(infixTypes(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    assertTrue(descendants[ScParenthesisedTypeElement](file).isEmpty)
    assertTrue(descendants[ScStableCodeReferenceImpl](file).isEmpty)
    assertTrue(descendants[ScTypePattern](file).isEmpty)
    assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)

  @Test
  def testTermAlternativePatternsKeepTheirOwner(): Unit =
    val source =
      """def alternatives(x: Any): Any = x match
        |  case A | B => "alternative"
        |  case other => "fallback"
        |""".stripMargin
    val file   = physical("MatchInfixTermAlternative.scala", source)
    assertEquals(2, descendants[ScCaseClauseImpl](file).size)
    assertTrue(infixTypes(file).isEmpty)

  @Test
  def testMatchOwnedInfixTypesStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source       =
      """def persisted(x: Any): Any = x match
        |  case y: (A | B) => "union"
        |  case y: (A & B) => "intersection"
        |""".stripMargin
    val file         = physical("MatchInfixTypesPersisted.scala", source)
    val fileInfo     = file.asInstanceOf[PsiFileImpl]
    val stubTree     = fileInfo.calcStubTree
    val stubList     = stubTree.getPlainList.asScala.toVector
    val beforeShape  = stubShape(stubList)
    val beforeIndex  = indexShape(stubList)
    assertFalse(beforeShape.exists(row => row.toLowerCase.contains("infix")))
    val output       = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(stubTree.getRoot, output)
    val restored     = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    val restoredList = restored.getPlainList.asScala.toVector
    assertEquals(beforeShape, stubShape(restoredList))
    assertEquals(beforeIndex, indexShape(restoredList))
    val reserialized = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, reserialized)
    assertArrayEquals("stub serialization must be byte-stable", output.toByteArray, reserialized.toByteArray)
    val stubBytes    = new String(output.toByteArray, java.nio.charset.StandardCharsets.ISO_8859_1)
    Vector(
      "match-pattern-infix-type",
      "match-pattern-infix-operator-union",
      "match-pattern-infix-operator-intersection"
    ).foreach(id => assertFalse(s"AST-only stubs must not embed $id", stubBytes.contains(id)))
    fileInfo.setTreeElementPointer(null)
    assertEquals(null, fileInfo.getTreeElement)
    val reparsed     = infixTypes(file)
    assertEquals(Vector("A | B", "A & B"), reparsed.map(_.getText))
    reparsed.foreach(w => assertSame(w, w.getNavigationElement))

  private def stubShape(stubs: Iterable[Stub]): Vector[String] = stubs.iterator
    .flatMap(stub =>
      Option(stub.getStubSerializer).map(serializer => s"${stub.getClass.getName}|${serializer.getExternalId}")
    )
    .toVector

  private def indexShape(stubs: Iterable[Stub]): Vector[String] =
    val result = Vector.newBuilder[String]
    val sink   = new IndexSink:
      override def occurrence[Psi <: com.intellij.psi.PsiElement, K](indexKey: StubIndexKey[K, Psi], value: K): Unit =
        result += s"${indexKey.toString}|${value.toString}"
    stubs.foreach(stub =>
      Option(stub.getStubSerializer).foreach(
        _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
      )
    )
    result.result()

  @Test
  def testMatchOwnedInfixTypesExposeExactElementTypeChildrenAndParentChain(): Unit =
    val source        =
      """def structure(x: Any): Any = x match
        |  case y: (A | B) => "union"
        |""".stripMargin
    val file          = physical("MatchInfixTypesStructure.scala", source)
    val infix         = infixTypes(file).head
    assertEquals(ScalaElementType.INFIX_TYPE, infix.getNode.getElementType)
    val children      = infix.getNode.getChildren(null).toVector.filterNot(_.getPsi.isInstanceOf[PsiWhiteSpace])
    assertEquals(
      Vector("ScSimpleTypeElementImpl", "ScStableCodeReferenceImpl", "ScSimpleTypeElementImpl"),
      children.map(_.getPsi.getClass.getSimpleName)
    )
    assertEquals(Vector("A", "|", "B"), children.map(_.getText))
    assertEquals(infix.getTextRange.getStartOffset, children.head.getStartOffset)
    assertEquals(infix.getTextRange.getEndOffset, children.last.getStartOffset + children.last.getTextLength)
    val parens        = infix.getParent.asInstanceOf[ScParenthesisedTypeElement]
    assertEquals("ScParenthesisedTypeElementImpl", parens.getClass.getSimpleName)
    val directPattern = parens.getParent
    assertTrue(directPattern.getText, directPattern.isInstanceOf[ScTypePattern])
    assertEquals("(A | B)", directPattern.getText)
    val typedPattern  = PsiTreeUtil.getParentOfType(parens, classOf[Sc3TypedPatternImpl])
    assertEquals("y: (A | B)", typedPattern.getText)
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    val caseClause    = PsiTreeUtil.getParentOfType(parens, classOf[ScCaseClauseImpl])
    assertEquals("ScCaseClauseImpl", caseClause.getClass.getSimpleName)
    assertEquals("case y: (A | B) => \"union\"", caseClause.getText)
    val matchExpr     = PsiTreeUtil.getParentOfType(parens, classOf[ScMatchImpl])
    assertEquals("ScMatchImpl", matchExpr.getClass.getSimpleName)
    assertTrue(matchExpr.getText.trim.startsWith("x match"))

  @Test
  def testMatchOwnedInfixTypesCoverDepthAndWidth(): Unit =
    val deepOperand = (1 to 16).foldLeft("B")((acc, _) => s"(A | $acc)")
    val deepSource  =
      s"""def deep(x: Any): Any = x match
         |  case y: $deepOperand => "deep"
         |""".stripMargin
    val deepFile    = physical("MatchInfixTypesDeep.scala", deepSource)
    val deepInfixes = infixTypes(deepFile)
    assertEquals(16, deepInfixes.size)
    assertEquals(deepOperand.stripPrefix("(").stripSuffix(")"), deepInfixes.head.getText)
    assertEquals("B", deepInfixes.last.rightOption.get.getText)
    deepInfixes.foreach(w => assertInfixContract(w, "|", "A", w.rightOption.get.getText))

    val wideOperand = (0 until 32).map(i => s"T$i").mkString(" | ")
    val wideSource  =
      s"""def wide(x: Any): Any = x match
         |  case y: ($wideOperand) => "wide"
         |""".stripMargin
    val wideFile    = physical("MatchInfixTypesWide.scala", wideSource)
    val wideInfixes = infixTypes(wideFile)
    assertEquals(31, wideInfixes.size)
    val outermost   = wideInfixes.maxBy(_.getText.length)
    assertEquals(wideOperand, outermost.getText)
    assertEquals("T0", wideInfixes.last.left.getText)
    assertEquals("T1", wideInfixes.last.rightOption.get.getText)
    assertEquals("|", wideInfixes.head.operation.getText)

  @Test
  def testMatchOwnedInfixTypesSurviveCopiesPointersAndTreeRestart(): Unit =
    val source =
      """def copied(x: Any): Any = x match
        |  case y: (A | B) => "union"
        |  case y: (A & B) => "intersection"
        |""".stripMargin
    val file   = physical("MatchInfixTypesPointer.scala", source)
    val copy   = physical("MatchInfixTypesPointerCopy.scala", source)
    assertEquals(infixTypes(file).map(_.getText), infixTypes(copy).map(_.getText))

    val pointer  = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(infixTypes(file).head)
    val fileInfo = file.asInstanceOf[PsiFileImpl]
    fileInfo.setTreeElementPointer(null)
    assertEquals(null, fileInfo.getTreeElement)
    val restored = pointer.getElement
    assertTrue(s"pointer must survive the tree restart: $restored", restored != null)
    assertEquals("A | B", restored.getText)
    assertTrue(restored.isInstanceOf[ScInfixTypeElementImpl])

    val copied = infixTypes(file).head.copy().asInstanceOf[ScInfixTypeElement]
    assertEquals("A | B", copied.getText)
    assertEquals(infixTypes(file).head.operation.getText, copied.operation.getText)
    assertInfixContract(copied, "|", "A", "B")

  @Test
  def testNonMatchInfixContextsStayExcluded(): Unit =
    val throwableClasses = "class E1 extends Exception\nclass E2 extends Exception\n"
    val excluded         = Vector(
      """def partial(x: Any): Any =
        |  val pf: PartialFunction[Any, Any] = { case y: (E1 | E2) => 1 }
        |  pf(x)
        |""".stripMargin,
      """def quoted(using q: scala.quoted.Quotes): scala.quoted.Expr[Any] =
        |  '{ (v: Any) => v match { case y: (E1 | E2) => y } }
        |""".stripMargin,
      """def refined(x: Any): Any = x match
        |  case y: (A { type X } | B) => 1
        |""".stripMargin,
      """def annotated(x: Any): Any = x match
        |  case y: (A @unchecked | B) => 1
        |""".stripMargin,
      """def typeLambda(x: Any): Any = x match
        |  case y: ([X] =>> (A | B)) => 1
        |""".stripMargin,
      """def contextFunction(x: Any): Any = x match
        |  case y: (String ?=> (E1 | E2)) => 1
        |""".stripMargin,
      """def dependentFunction(x: Any): Any = x match
        |  case y: ((x: E1) => (E1 | E2)) => 1
        |""".stripMargin,
      """def polyFunction(x: Any): Any = x match
        |  case y: ([X] => (E1 | E2) => E1) => 1
        |""".stripMargin,
      """def namedTuple(x: Any): Any = x match
        |  case y: ((a: E1, b: E2)) => 1
        |""".stripMargin,
      """def matchType(x: Any): Any = x match
        |  case y: ((E1 match { case t => t }) | E2) => 1
        |""".stripMargin,
      """def projectionQualifier(x: Any): Any = x match
        |  case y: ((P)#T | E1) => 1
        |""".stripMargin,
      """def byName(x: Any): Any = x match
        |  case y: ((=> E1) | E2) => 1
        |""".stripMargin,
      """def capture(x: Any): Any = x match
        |  case y: (E1^ | E2) => 1
        |""".stripMargin,
      """def repeated(x: Any): Any = x match
        |  case y: (E1* | E2) => 1
        |""".stripMargin,
      """def trapping(x: Any): Any =
        |  try x.asInstanceOf[Int]
        |  catch { case y: (E1 | E2) => 0 }
        |""".stripMargin,
      """def generating(x: Any): Any =
        |  for case y: (E1 | E2) <- Seq(x) yield y
        |""".stripMargin
    )
    excluded.zipWithIndex.foreach { case (body, index) =>
      val source  = throwableClasses + body.stripMargin
      val file    = pendingFile(s"MatchInfixExcluded$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"non-match infix context must fail closed (source $index): $failure", failure.isDefined)
      assertTrue(infixTypes(file).isEmpty)
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
    }

    val ordinary       =
      """type T = E1 | E2
        |def use(t: T): T = t
        |""".stripMargin
    val ordinarySource = throwableClasses + ordinary
    val ordinaryFile   = physical("MatchInfixOrdinaryScope.scala", ordinarySource)
    val ordinaryInfix  = infixTypes(ordinaryFile)
    assertEquals(Vector("E1 | E2"), ordinaryInfix.map(_.getText))
    assertTrue(descendants[ScMatchImpl](ordinaryFile).isEmpty)

    val definitionSource = throwableClasses +
      """def defined(x: Any): (E1 | E2) = ???
        |""".stripMargin
    val definitionFile   = physical("MatchInfixDefinitionScope.scala", definitionSource)
    val definitionInfix  = infixTypes(definitionFile)
    assertEquals(Vector("E1 | E2"), definitionInfix.map(_.getText))
    assertTrue(descendants[ScMatchImpl](definitionFile).isEmpty)

  @Test
  def testMatchOwnedInfixTypeEditsTransitionBetweenShapesAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: INFIX => "i"
        |""".stripMargin
    val file                               = physical("MatchInfixTypesEdit.scala", template.replace("INFIX", "(A | B)"))
    assertEquals(Vector("A | B"), infixTypes(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("INFIX", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("(A & B)")
    assertEquals(Vector("A & B"), infixTypes(file).map(_.getText))
    replace("(A :+: B)")
    assertTrue(infixTypes(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(A |")
    assertTrue(infixTypes(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(A | B | C)")
    assertEquals(Vector("A | B | C", "A | B"), infixTypes(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.PsiFile
import com.intellij.psi.stubs.{IndexSink, ObjectStubSerializer, Stub, StubIndexKey}
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParenthesisedTypeElement
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  ScParenthesisedPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.{
  ScParenthesisedTypeElementImpl,
  ScParameterizedTypeElementImpl,
  ScSimpleTypeElementImpl,
  ScTupleTypeElementImpl,
  ScTypeArgsImpl,
  ScTypeProjectionImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternParenthesizedTypePsiTest extends Scala3CompatTestCase:
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

  private def wrappers(file: PsiFile): Vector[ScParenthesisedTypeElementImpl] =
    descendants[ScParenthesisedTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)

  private def delimiterLeaf(element: ScParenthesisedTypeElement, text: String): PsiElement =
    val leaves = PsiTreeUtil.findChildrenOfType(element, classOf[PsiElement]).asScala.toVector
    leaves
      .find(child => child.getText == text && child.getFirstChild == null)
      .getOrElse(throw new AssertionError(s"delimiter leaf '$text' not found in '${element.getText}'"))

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

  private def assertContained(container: PsiElement, inner: PsiElement): Unit =
    assertTrue(
      s"'${inner.getText}' must sit inside '${container.getText}'",
      container.getTextRange.getStartOffset <= inner.getTextRange.getStartOffset &&
        inner.getTextRange.getEndOffset <= container.getTextRange.getEndOffset
    )

  @Test
  def testMatchOwnedParenthesizedTypesProduceNativePsiAcrossTheAdmittedMatrix(): Unit =
    val source  =
      """def shapes(x: Any): Any = x match
        |  case y: (A) => "single"
        |  case y: ((A)) => "nested"
        |  case y: (F[A]) => "applied-wrapper"
        |  case y: (F)[A] => "wrapped-constructor"
        |  case y: F[(A)] => "wrapped-argument"
        |  case y: (A, (B)) => "tuple-component"
        |  case y: ((Int, String)) => "wrapped-tuple"
        |  case y: F[? >: (A) <: (B)] => "wildcard-bounds"
        |  case y: ( /* c1 */ A /* c2 */ ) => "trivia"
        |""".stripMargin
    val file    = physical("MatchParenthesizedTypes1.scala", source)
    assertEquals(9, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(9, descendants[Sc3TypedPatternImpl](file).size)
    val wrapped = wrappers(file)
    assertEquals(
      Vector(
        "(A)",
        "((A))",
        "(A)",
        "(F[A])",
        "(F)",
        "(A)",
        "(B)",
        "((Int, String))",
        "(A)",
        "(B)",
        "( /* c1 */ A /* c2 */ )"
      ),
      wrapped.map(_.getText)
    )

    // Direct wrapper: exact ranges, native delimiter leaves, direct children, ScTypePattern parent.
    val single = wrapped.head
    assertEquals(source.indexOf("(A)"), single.getTextRange.getStartOffset)
    assertEquals(source.indexOf("(A)") + 3, single.getTextRange.getEndOffset)
    assertEquals("A", single.innerElement.toVector.map(_.getText).mkString)
    single.getParent match
      case pattern: ScTypePattern => assertEquals(single.getTextRange, pattern.getTextRange)
      case other                  => fail(s"unexpected direct wrapper parent: ${other.getClass.getName}")
    assertEquals(Vector("(", "A", ")"), single.getNode.getChildren(null).toVector.map(_.getText))
    assertEquals(ScalaTokenTypes.tLPARENTHESIS, delimiterLeaf(single, "(").getNode.getElementType)
    assertEquals(ScalaTokenTypes.tRPARENTHESIS, delimiterLeaf(single, ")").getNode.getElementType)
    assertSame(single, delimiterLeaf(single, "(").getParent)
    assertSame(single, delimiterLeaf(single, ")").getParent)
    assertPhysicalContract(single, source)

    // Each nesting level owns only its own delimiters, linked through innerElement/sameTreeParent.
    // Local first/last delimiter selection is proven per level: the outer wrapper's own leaves are
    // its tLPARENTHESIS and tRPARENTHESIS even though its inner child contains another pair.
    val nestedOuter       = wrapped.find(_.getText == "((A))").get
    val nestedInner       = nestedOuter.innerElement.get.asInstanceOf[ScParenthesisedTypeElementImpl]
    assertEquals("(A)", nestedInner.getText)
    assertSame(nestedOuter, nestedInner.getParent)
    assertSame(nestedOuter, nestedInner.sameTreeParent.get)
    assertEquals("A", nestedInner.innerElement.toVector.map(_.getText).mkString)
    assertEquals(Vector("(", "(A)", ")"), nestedOuter.getNode.getChildren(null).toVector.map(_.getText))
    assertEquals(Vector("(", "A", ")"), nestedInner.getNode.getChildren(null).toVector.map(_.getText))
    val nestedOuterLeaves = nestedOuter.getNode.getChildren(null).toVector.filter(_.getFirstChildNode == null)
    val nestedInnerLeaves = nestedInner.getNode.getChildren(null).toVector.filter(_.getFirstChildNode == null)
    assertEquals(ScalaTokenTypes.tLPARENTHESIS, nestedOuterLeaves.head.getElementType)
    assertEquals(ScalaTokenTypes.tRPARENTHESIS, nestedOuterLeaves.last.getElementType)
    assertEquals(ScalaTokenTypes.tLPARENTHESIS, nestedInnerLeaves.head.getElementType)
    assertEquals(ScalaTokenTypes.tRPARENTHESIS, nestedInnerLeaves.last.getElementType)
    assertSame(nestedOuter.getNode, nestedOuterLeaves.head.getTreeParent)
    assertSame(nestedOuter.getNode, nestedOuterLeaves.last.getTreeParent)
    assertSame(nestedInner.getNode, nestedInnerLeaves.head.getTreeParent)
    assertSame(nestedInner.getNode, nestedInnerLeaves.last.getTreeParent)

    // Applied wrapper forms keep the wrapper inside the parameterized element.
    val appliedWrapper = wrapped.find(_.getText == "(F[A])").get
    val parameterized  = descendants[ScParameterizedTypeElementImpl](file).find(_.getText == "F[A]").get
    assertContained(appliedWrapper, parameterized)
    val constructor    = wrapped.find(_.getText == "(F)").get
    val applied        = descendants[ScParameterizedTypeElementImpl](file).find(_.getText == "(F)[A]").get
    assertContained(applied, constructor)
    val argument       = wrapped.filter(_.getText == "(A)").drop(2).head
    val args           = descendants[ScTypeArgsImpl](file).find(_.getText.contains("(A)")).get
    assertContained(args, argument)

    // The wrapper around a tuple leaves the tuple production in place with its own components.
    val wrappedTuple   = wrapped.find(_.getText == "((Int, String))").get
    val tupleInner     = wrappedTuple.innerElement.get.asInstanceOf[ScTupleTypeElementImpl]
    assertEquals("(Int, String)", tupleInner.getText)
    assertEquals(Vector("Int", "String"), tupleInner.components.map(_.getText))
    val tupleComponent = wrapped.find(_.getText == "(B)").get
    val tupleElement   = descendants[ScTupleTypeElementImpl](file).find(_.getText == "(A, (B))").get
    assertContained(tupleElement, tupleComponent)

    wrapped.foreach(assertPhysicalContract(_, source))

  @Test
  def testMatchOwnedParenthesizedTypesComposeRecursivelyAcrossDepths(): Unit =
    val depth  = 16
    val source =
      s"""def deep(x: Any): Any = x match
         |  case y: ${"(" * depth}A${")" * depth} => "deep"
         |  case y: F[((A, (B)))] => "composed"
         |  case y: ((F[Box[? <: (A)]])) => "bounded"
         |""".stripMargin
    val file   = physical("MatchParenthesizedTypesDeep.scala", source)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)

    // The outermost deep wrapper anchors under the typed pattern; innerElement walks exactly the
    // 16 wrappers down, and every level links back through sameTreeParent.
    val top                                         = wrappers(file).find(w => w.getParent.isInstanceOf[ScTypePattern] && w.getText.length > 4).get
    var current: Option[ScParenthesisedTypeElement] = Some(top)
    var levels                                      = 0
    while current.isDefined do
      levels += 1
      current = current.get.innerElement.collect { case nested: ScParenthesisedTypeElement => nested }
    assertEquals(depth, levels)
    var level                                       = top
    for _ <- 1 until depth do
      val nested = level.innerElement.get.asInstanceOf[ScParenthesisedTypeElementImpl]
      assertSame(level, nested.sameTreeParent.get)
      level = nested
    assertEquals("A", level.innerElement.toVector.map(_.getText).mkString)

    val composed      = wrappers(file).find(_.getText == "((A, (B)))").get
    val composedTuple = composed.innerElement.get.asInstanceOf[ScTupleTypeElementImpl]
    assertEquals(Vector("A", "(B)"), composedTuple.components.map(_.getText))
    val bounded       = wrappers(file).find(_.getText.contains("Box")).get
    assertEquals("((F[Box[? <: (A)]]))", bounded.getText)
    wrappers(file).foreach(assertPhysicalContract(_, source))

  @Test
  def testMatchOwnedParenthesizedTypesOwnGivenPatternPositions(): Unit =
    val source  =
      """def givens(x: Any): Any = x match
        |  case given (A) => "anon"
        |  case ord @ given (B) => "named"
        |  case _ @ given (C) => "wildcardBinder"
        |  case `back-tick` @ given (D) => "backtickedBinder"
        |  case given ((Int, String)) => "givenDoubleParen"
        |""".stripMargin
    val file    = physical("MatchParenthesizedTypesGiven.scala", source)
    val givens  = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(5, givens.size)
    assertEquals(
      Vector("given (A)", "given (B)", "given (C)", "given (D)", "given ((Int, String))"),
      givens.map(_.getText)
    )
    val wrapped = wrappers(file)
    assertEquals(Vector("(A)", "(B)", "(C)", "(D)", "((Int, String))"), wrapped.map(_.getText))
    assertEquals(5, descendants[ScCaseClauseImpl](file).size)
    wrapped.foreach(assertPhysicalContract(_, source))
    assertSame(
      givens.last,
      PsiTreeUtil.getParentOfType(wrapped.last, classOf[ScGivenPatternImpl])
    )

  @Test
  def testMatchOwnedParenthesizedTypesWrapOwnedSelectFamilies(): Unit =
    val source     =
      """def selects(x: Any): Any = x match
        |  case y: (p.q.T) => "dotted"
        |  case y: (Outer#T) => "projection"
        |""".stripMargin
    val file       = physical("MatchParenthesizedTypesSelect.scala", source)
    assertEquals(2, descendants[ScCaseClauseImpl](file).size)
    val wrapped    = wrappers(file)
    assertEquals(Vector("(p.q.T)", "(Outer#T)"), wrapped.map(_.getText))
    val dotted     = wrapped.head.innerElement.get.asInstanceOf[ScSimpleTypeElementImpl]
    assertEquals("p.q.T", dotted.getText)
    val projection = wrapped.last.innerElement.get.asInstanceOf[ScTypeProjectionImpl]
    assertEquals("Outer#T", projection.getText)
    wrapped.foreach(assertPhysicalContract(_, source))

  @Test
  def testPatternAndTypeParensStayDisjointInTheSameCase(): Unit =
    val source   =
      """def combined(x: Any): Any = x match
        |  case (x: (A)) => "combined"
        |  case ((y: (B))) => "doubly-combined"
        |""".stripMargin
    val file     = physical("MatchParenthesizedTypesCombined.scala", source)
    val wrapped  = wrappers(file)
    assertEquals(Vector("(A)", "(B)"), wrapped.map(_.getText))
    val patterns = descendants[ScParenthesisedPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("(x: (A))", "((y: (B)))", "(y: (B))"), patterns.map(_.getText))
    wrapped.foreach: wrapper =>
      val containers = patterns.filter(pattern =>
        pattern.getTextRange.getStartOffset < wrapper.getTextRange.getStartOffset &&
          wrapper.getTextRange.getEndOffset < pattern.getTextRange.getEndOffset
      )
      val container  = containers.maxBy(_.getTextRange.getStartOffset)
      assertContained(container, wrapper)
    assertEquals(2, descendants[ScCaseClauseImpl](file).size)
    wrapped.foreach(assertPhysicalContract(_, source))

  @Test
  def testUnsupportedWrappedFamiliesStayFailClosed(): Unit =
    val sources = Vector(
      """def qual(x: Any): Any = x match
        |  case y: (P)#T => 1
        |""".stripMargin,
      """def function(x: Any): Any = x match
        |  case y: (A => B) => 1
        |""".stripMargin,
      """def refinement(x: Any): Any = x match
        |  case y: (A { type X }) => 1
        |""".stripMargin,
      """def annotation(x: Any): Any = x match
        |  case y: (A @unchecked) => 1
        |""".stripMargin,
      """def intersection(x: Any): Any = x match
        |  case y: (A & B) => 1
        |""".stripMargin,
      """def union(x: Any): Any = x match
        |  case y: (A | B) => 1
        |""".stripMargin,
      """def namedTuple(x: Any): Any = x match
        |  case y: ((a: A, b: B)) => 1
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchParenthesizedUnsupported$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported wrapped shape should fail closed (source $index): $failure", failure.isDefined)
      assertTrue(
        s"failure should mention uncovered shape (source $index)",
        failure.get.toString.contains("UncoveredCompilerShape")
      )
      assertTrue(wrappers(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    }

  @Test
  def testMalformedParensRejectTheWholeFileWithoutRecovery(): Unit =
    val sources = Vector(
      """def unit(x: Any): Any = x match
        |  case y: () => 1
        |""".stripMargin,
      """def trailing(x: Any): Any = x match
        |  case y: (A,) => 1
        |""".stripMargin,
      """def missing(x: Any): Any = x match
        |  case y: (A
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchParenthesizedMalformed$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"malformed parens must fail closed (source $index)", failure.isDefined)
      assertTrue(wrappers(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    }

  @Test
  def testMixedFileAdmittedAndUnsupportedWrappersFailAtomically(): Unit =
    val source  =
      """def mixed(x: Any): Any = x match
        |  case y: (A) => "admitted"
        |  case y: (A => B) => "unsupported"
        |""".stripMargin
    val file    = pendingFile("MatchParenthesizedMixedAtomicity.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"mixed wrappers must fail the whole file closed: $failure", failure.isDefined)
    assertTrue(wrappers(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)

  @Test
  def testMatchOwnedParenthesizedTypesStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source       =
      """def persisted(x: Any): Any = x match
        |  case y: (A) => "single"
        |  case y: ((B)) => "nested"
        |""".stripMargin
    val file         = physical("MatchParenthesizedTypesPersisted.scala", source)
    val fileInfo     = file.asInstanceOf[PsiFileImpl]
    val stubTree     = fileInfo.calcStubTree
    val stubList     = stubTree.getPlainList.asScala.toVector
    assertTrue(
      "parenthesized types must not create stubs",
      stubList.forall(stub => !stub.getClass.getSimpleName.toLowerCase.contains("parenthes"))
    )
    val beforeShape  = stubShape(stubList)
    val beforeIndex  = indexShape(stubList)
    assertFalse(beforeShape.exists(row => row.toLowerCase.contains("parenthes")))
    assertFalse(beforeIndex.exists(row => row.toLowerCase.contains("parenthes")))
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
    fileInfo.setTreeElementPointer(null)
    assertEquals(null, fileInfo.getTreeElement)
    val reparsed     = wrappers(file)
    assertEquals(Vector("(A)", "((B))", "(B)"), reparsed.map(_.getText))
    reparsed.foreach(wrapper => assertSame(wrapper, wrapper.getNavigationElement))

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
  def testMatchOwnedParenthesizedTypeEditsTransitionBetweenShapesAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: WRAPPED => "w"
        |""".stripMargin
    val file                               = physical("MatchParenthesizedTypesEdit.scala", template.replace("WRAPPED", "(A)"))
    assertEquals(Vector("(A)"), wrappers(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("WRAPPED", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("((A))")
    assertEquals(Vector("((A))", "(A)"), wrappers(file).map(_.getText))
    replace("(A => B)")
    assertTrue(wrappers(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(A")
    assertTrue(wrappers(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(F[(A)])")
    assertEquals(Vector("(F[(A)])", "(A)"), wrappers(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)

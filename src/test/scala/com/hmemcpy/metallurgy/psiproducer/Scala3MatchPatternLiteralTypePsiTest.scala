package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{IndexSink, ObjectStubSerializer, Stub, StubIndexKey}
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParenthesisedTypeElement
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScLiteralTypeElementImpl
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternLiteralTypePsiTest extends Scala3CompatTestCase:
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

  private def literalTypes(file: PsiFile): Vector[ScLiteralTypeElementImpl] =
    descendants[ScLiteralTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)

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
  def testMatchOwnedLiteralTypesProduceNativePsiAcrossTheScalarFamilies(): Unit =
    val source  =
      """def shapes(x: Any): Any = x match
        |  case y: 42 => "int"
        |  case y: -42 => "folded"
        |  case y: 0x1F => "hex"
        |  case y: 2147483647 => "max"
        |  case y: -2147483648 => "min"
        |  case y: 1L => "long"
        |  case y: 1.0f => "float"
        |  case y: 1.0 => "double"
        |  case y: 42l => "lowercase-long"
        |  case y: 1.0d => "d-suffix"
        |  case y: 'a' => "char"
        |  case y: "lit" => "string"
        |  case y: true => "true"
        |  case y: false => "false"
        |  case y: 42 /* c */ => "trivia-comment"
        |""".stripMargin
    val file    = physical("MatchLiteralTypes1.scala", source)
    assertEquals(15, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(15, descendants[Sc3TypedPatternImpl](file).size)
    val wrapped = literalTypes(file)
    assertEquals(
      Vector(
        "42",
        "-42",
        "0x1F",
        "2147483647",
        "-2147483648",
        "1L",
        "1.0f",
        "1.0",
        "42l",
        "1.0d",
        "'a'",
        "\"lit\"",
        "true",
        "false",
        "42"
      ),
      wrapped.map(_.getText)
    )

    val first = wrapped.head
    assertEquals(source.indexOf("42"), first.getTextRange.getStartOffset)
    assertEquals(source.indexOf("42") + 2, first.getTextRange.getEndOffset)
    first.getParent match
      case pattern: ScTypePattern => assertEquals(first.getTextRange, pattern.getTextRange)
      case other                  => fail(s"unexpected literal parent: ${other.getClass.getName}")

    val innerClasses = wrapped.map(inner => inner.getLiteral.getClass.getSimpleName)
    assertEquals(
      Vector(
        "ScIntegerLiteralImpl",
        "ScIntegerLiteralImpl",
        "ScIntegerLiteralImpl",
        "ScIntegerLiteralImpl",
        "ScIntegerLiteralImpl",
        "ScLongLiteralImpl",
        "ScFloatLiteralImpl",
        "ScDoubleLiteralImpl",
        "ScLongLiteralImpl",
        "ScDoubleLiteralImpl",
        "ScCharLiteralImpl",
        "ScStringLiteralImpl",
        "ScBooleanLiteralImpl",
        "ScBooleanLiteralImpl",
        "ScIntegerLiteralImpl"
      ),
      innerClasses
    )
    assertEquals(java.lang.Integer.valueOf(42), wrapped.head.getLiteral.getValue)
    assertTrue(wrapped.forall(_.getLiteral.isSimpleLiteral))
    wrapped.foreach(w => assertSame(w, w.getNavigationElement))
    wrapped.foreach(assertPhysicalContract(_, source))
    var visitedScala = false
    val visitor      = new ScalaElementVisitor:
      override def visitScalaElement(value: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement): Unit =
        if value == first then visitedScala = true
    first.accept(visitor)
    assertTrue(visitedScala)

  @Test
  def testMatchOwnedLiteralTypesOwnGivenPatternPositions(): Unit =
    val source  =
      """def givens(x: Any): Any = x match
        |  case given 42 => "anon"
        |  case n @ given -42 => "named"
        |  case _ @ given 'a' => "wildcardBinder"
        |  case `bt` @ given "lit" => "backtickedBinder"
        |""".stripMargin
    val file    = physical("MatchLiteralTypesGiven.scala", source)
    val givens  = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(4, givens.size)
    val wrapped = literalTypes(file)
    assertEquals(Vector("42", "-42", "'a'", "\"lit\""), wrapped.map(_.getText))
    assertEquals(4, descendants[ScCaseClauseImpl](file).size)
    wrapped.foreach(w => assertSame(w, w.getNavigationElement))

  @Test
  def testMatchOwnedLiteralTypesComposeWithOwnedTypeFamilies(): Unit =
    val source  =
      """def composed(x: Any): Any = x match
        |  case y: (42) => "wrapped"
        |  case y: Box[42] => "applied"
        |  case y: (A, 42) => "tuple"
        |  case y: Box[? <: 42] => "bounds"
        |  case (x: 42) => "pattern-and-type"
        |  case y: ((42)) => "nested"
        |""".stripMargin
    val file    = physical("MatchLiteralTypesComposed.scala", source)
    assertEquals(6, descendants[ScCaseClauseImpl](file).size)
    val wrapped = literalTypes(file)
    assertEquals(Vector("42", "42", "42", "42", "42", "42"), wrapped.map(_.getText))
    wrapped.head.getParent match
      case parens: ScParenthesisedTypeElement => assertEquals("(42)", parens.getText)
      case other                              => fail(s"unexpected wrapper parent: ${other.getClass.getName}")
    wrapped.foreach(w => assertSame(w, w.getNavigationElement))

  @Test
  def testUnsupportedLiteralShapesStayFailClosed(): Unit =
    val sources = Vector(
      """def literalSuffix(x: Any): Any = x match
        |  case y: 1.type => 1
        |""".stripMargin,
      """def nullType(x: Any): Any = x match
        |  case y: null => 1
        |""".stripMargin,
      """def unitType(x: Any): Any = x match
        |  case y: () => 1
        |""".stripMargin,
      """def outOfRange(x: Any): Any = x match
        |  case y: 99999999999999999999 => 1
        |""".stripMargin,
      """def plusSign(x: Any): Any = x match
        |  case y: +42 => 1
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchLiteralUnsupported$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported literal shape should fail closed (source $index): $failure", failure.isDefined)
      assertTrue(literalTypes(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScMatchImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    }

  @Test
  def testMixedFileAdmittedAndUnsupportedLiteralsFailAtomically(): Unit =
    val source  =
      """def mixed(x: Any): Any = x match
        |  case y: 42 => "admitted"
        |  case y: null => "unsupported"
        |""".stripMargin
    val file    = pendingFile("MatchLiteralMixedAtomicity.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"mixed literals must fail the whole file closed: $failure", failure.isDefined)
    assertTrue(literalTypes(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)

  @Test
  def testMatchOwnedLiteralTypesStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source       =
      """def persisted(x: Any): Any = x match
        |  case y: 42 => "int"
        |  case y: "lit" => "string"
        |""".stripMargin
    val file         = physical("MatchLiteralTypesPersisted.scala", source)
    val fileInfo     = file.asInstanceOf[PsiFileImpl]
    val stubTree     = fileInfo.calcStubTree
    val stubList     = stubTree.getPlainList.asScala.toVector
    val beforeShape  = stubShape(stubList)
    val beforeIndex  = indexShape(stubList)
    assertFalse(beforeShape.exists(row => row.toLowerCase.contains("literaltype")))
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
    val reparsed     = literalTypes(file)
    assertEquals(Vector("42", "\"lit\""), reparsed.map(_.getText))
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
  def testMatchOwnedLiteralTypeEditsTransitionBetweenShapesAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: LITERAL => "l"
        |""".stripMargin
    val file                               = physical("MatchLiteralTypesEdit.scala", template.replace("LITERAL", "42"))
    assertEquals(Vector("42"), literalTypes(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("LITERAL", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("-42")
    assertEquals(Vector("-42"), literalTypes(file).map(_.getText))
    replace("1.type")
    assertTrue(literalTypes(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(42)")
    assertEquals(Vector("42"), literalTypes(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)

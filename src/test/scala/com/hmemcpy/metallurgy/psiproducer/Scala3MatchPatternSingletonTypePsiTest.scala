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
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParenthesisedTypeElement, ScSimpleTypeElement}
import org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScSimpleTypeElementImpl
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternSingletonTypePsiTest extends Scala3CompatTestCase:
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

  private def singletons(file: PsiFile): Vector[ScSimpleTypeElementImpl] =
    descendants[ScSimpleTypeElementImpl](file)
      .filter(_.isSingleton)
      .sortBy(_.getTextRange.getStartOffset)

  private def singletonTypePsiAbsent(file: PsiFile): Boolean =
    singletons(file).isEmpty && descendants[ScStableCodeReferenceImpl](file).isEmpty

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

  private def assertSingletonContract(
      singleton: ScSimpleTypeElementImpl,
      segments: Vector[String]
  ): Unit =
    assertTrue(singleton.isSingleton)
    assertSame(singleton, singleton.getNavigationElement)
    val reference = singleton.reference.get.asInstanceOf[ScStableCodeReferenceImpl]
    assertSame(singleton, reference.getParent)
    assertEquals(segments.last, reference.nameId.getText.replace("`", ""))
    var qualifier = reference.qualifier
    segments
      .dropRight(1)
      .reverse
      .foreach: segment =>
        val qual = qualifier.getOrElse(throw new AssertionError(s"missing qualifier segment $segment"))
        assertTrue(qual.isInstanceOf[ScStableCodeReferenceImpl])
        assertEquals(segment, qual.asInstanceOf[ScStableCodeReferenceImpl].nameId.getText)
        qualifier = qual.asInstanceOf[ScStableCodeReferenceImpl].qualifier
    assertTrue(s"qualifier chain must end at ${segments.head}", qualifier.isEmpty)
    assertSame(reference, singleton.pathElement)
    assertPhysicalContract(singleton, singleton.getContainingFile.getText)
    val children  = singleton.getChildren.toVector
    assertEquals(Vector(reference), children.collect { case value: ScStableCodeReferenceImpl => value })
    val dotLeaf   = singleton.getContainingFile.findElementAt(reference.getTextRange.getEndOffset)
    assertNotNull(dotLeaf)
    assertEquals(".", dotLeaf.getText)
    assertEquals(ScalaTokenTypes.tDOT, dotLeaf.getNode.getElementType)
    val typeLeaf  = singleton.getContainingFile.findElementAt(singleton.getTextRange.getEndOffset - 1)
    assertNotNull(typeLeaf)
    assertEquals("type", typeLeaf.getText)
    assertEquals(ScalaTokenTypes.kTYPE, typeLeaf.getNode.getElementType)
    Seq(dotLeaf, typeLeaf).foreach(leaf =>
      assertSame(singleton, PsiTreeUtil.getParentOfType(leaf, classOf[ScSimpleTypeElementImpl]))
    )

  @Test
  def testMatchOwnedReferenceSingletonsProduceNativePsiAcrossTheAdmittedMatrix(): Unit =
    val source  =
      """def shapes(x: Any): Any = x match
        |  case y: stable.type => "ident"
        |  case y: owner.stable.type => "select"
        |  case y: pkg.owner.stable.type => "deep"
        |  case y: `stable`.type => "backticked"
        |  case y: stable. type => "trivia-gap"
        |""".stripMargin
    val file    = physical("MatchSingletonTypes1.scala", source)
    assertEquals(5, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[ScMatchImpl](file).size)
    assertEquals(5, descendants[Sc3TypedPatternImpl](file).size)
    val wrapped = singletons(file)
    assertEquals(
      Vector("stable.type", "owner.stable.type", "pkg.owner.stable.type", "`stable`.type", "stable. type"),
      wrapped.map(_.getText)
    )

    val first = wrapped.head
    assertEquals(source.indexOf("stable.type"), first.getTextRange.getStartOffset)
    assertEquals(source.indexOf("stable.type") + "stable.type".length, first.getTextRange.getEndOffset)
    first.getParent match
      case pattern: ScTypePattern => assertEquals(first.getTextRange, pattern.getTextRange)
      case other                  => fail(s"unexpected singleton parent: ${other.getClass.getName}")
    assertSingletonContract(first, Vector("stable"))
    assertSingletonContract(wrapped(1), Vector("owner", "stable"))
    assertSingletonContract(wrapped(2), Vector("pkg", "owner", "stable"))
    assertSingletonContract(wrapped(3), Vector("stable"))
    assertSingletonContract(wrapped(4), Vector("stable"))

    var visitedSimple = false
    val visitor       = new ScalaElementVisitor:
      override def visitSimpleTypeElement(value: ScSimpleTypeElement): Unit = visitedSimple = true
    first.accept(visitor)
    assertTrue(visitedSimple)

  @Test
  def testMatchOwnedReferenceSingletonsOwnGivenPatternPositions(): Unit =
    val source        =
      """def givens(x: Any): Any = x match
        |  case given stable.type => "anon"
        |  case n @ given owner.stable.type => "named"
        |  case _ @ given stable.type => "wildcardBinder"
        |  case `bt` @ given stable.type => "backtickedBinder"
        |""".stripMargin
    val file          = physical("MatchSingletonTypesGiven.scala", source)
    val givens        = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(4, givens.size)
    assertEquals(
      Vector("given stable.type", "given owner.stable.type", "given stable.type", "given stable.type"),
      givens.map(_.getText)
    )
    val wrapped       = singletons(file)
    assertEquals(
      Vector("stable.type", "owner.stable.type", "stable.type", "stable.type"),
      wrapped.map(_.getText)
    )
    assertEquals(4, descendants[ScCaseClauseImpl](file).size)
    val givenSegments = Vector(
      Vector("stable"),
      Vector("owner", "stable"),
      Vector("stable"),
      Vector("stable")
    )
    wrapped.zip(givenSegments).foreach((wrapper, segments) => assertSingletonContract(wrapper, segments))

  @Test
  def testMatchOwnedReferenceSingletonsComposeWithOwnedTypeFamilies(): Unit =
    val source  =
      """def composed(x: Any): Any = x match
        |  case y: (stable.type) => "wrapped"
        |  case y: Box[stable.type] => "applied"
        |  case y: (A, stable.type) => "tuple"
        |  case y: Box[? <: stable.type] => "bounds"
        |  case (x: stable.type) => "pattern-and-type"
        |  case y: ((stable.type)) => "nested"
        |""".stripMargin
    val file    = physical("MatchSingletonTypesComposed.scala", source)
    assertEquals(6, descendants[ScCaseClauseImpl](file).size)
    val wrapped = singletons(file)
    assertEquals(
      Vector("stable.type", "stable.type", "stable.type", "stable.type", "stable.type", "stable.type"),
      wrapped.map(_.getText)
    )
    wrapped.foreach(w => assertSingletonContract(w, Vector("stable")))

    // The parenthesized wrapper stays owned by the parenthesized production around the singleton.
    wrapped.head.getParent match
      case parens: ScParenthesisedTypeElement => assertEquals("(stable.type)", parens.getText)
      case other                              => fail(s"unexpected wrapper parent: ${other.getClass.getName}")

    // The typed pattern inside a term-parenthesized pattern keeps both owners disjoint.
    val patternAndType = wrapped(4)
    val typedPatterns  = descendants[Sc3TypedPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertTrue(typedPatterns.exists(_.getTextRange.contains(patternAndType.getTextRange)))

  @Test
  def testUnsupportedSingletonReferencesStayFailClosed(): Unit =
    val sources = Vector(
      """def literal(x: Any): Any = x match
        |  case y: 1.type => 1
        |""".stripMargin,
      """def stringLiteral(x: Any): Any = x match
        |  case y: "s".type => 1
        |""".stripMargin,
      """def thisType(x: Any): Any = x match
        |  case y: this.type => 1
        |""".stripMargin,
      """def superType(x: Any): Any = x match
        |  case y: super.type => 1
        |""".stripMargin,
      """def hashQualifier(x: Any): Any = x match
        |  case y: Outer#Inner.type => 1
        |""".stripMargin,
      """def appliedQualifier(x: Any): Any = x match
        |  case y: F[Int].type => 1
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchSingletonUnsupported$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported singleton shape should fail closed (source $index): $failure", failure.isDefined)
      assertTrue(singletonTypePsiAbsent(file))
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    }

  @Test
  def testMixedFileAdmittedAndUnsupportedSingletonsFailAtomically(): Unit =
    val source  =
      """def mixed(x: Any): Any = x match
        |  case y: stable.type => "admitted"
        |  case y: this.type => "unsupported"
        |""".stripMargin
    val file    = pendingFile("MatchSingletonMixedAtomicity.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"mixed singletons must fail the whole file closed: $failure", failure.isDefined)
    assertTrue(singletonTypePsiAbsent(file))
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)

  @Test
  def testMatchOwnedReferenceSingletonsStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source       =
      """def persisted(x: Any): Any = x match
        |  case y: stable.type => "ident"
        |  case y: owner.stable.type => "select"
        |""".stripMargin
    val file         = physical("MatchSingletonTypesPersisted.scala", source)
    val fileInfo     = file.asInstanceOf[PsiFileImpl]
    val stubTree     = fileInfo.calcStubTree
    val stubList     = stubTree.getPlainList.asScala.toVector
    val beforeShape  = stubShape(stubList)
    val beforeIndex  = indexShape(stubList)
    assertFalse(beforeShape.exists(row => row.toLowerCase.contains("singleton")))
    assertFalse(beforeIndex.exists(row => row.toLowerCase.contains("singleton")))
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
    val reparsed     = singletons(file)
    assertEquals(Vector("stable.type", "owner.stable.type"), reparsed.map(_.getText))
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
  def testMatchOwnedReferenceSingletonEditsTransitionBetweenShapesAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: SINGLETON => "s"
        |""".stripMargin
    val file                               = physical("MatchSingletonTypesEdit.scala", template.replace("SINGLETON", "stable.type"))
    assertEquals(Vector("stable.type"), singletons(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("SINGLETON", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("owner.stable.type")
    assertEquals(Vector("owner.stable.type"), singletons(file).map(_.getText))
    replace("1.type")
    assertTrue(singletons(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("this.type")
    assertTrue(singletons(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(stable.type)")
    assertEquals(Vector("stable.type"), singletons(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)

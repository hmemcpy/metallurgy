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
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.{ScSimpleTypeElementImpl, ScTypeProjectionImpl}
import org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternStableSelectPsiTest extends Scala3CompatTestCase:
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

  private def astDescendant[T <: PsiElement: ClassTag](element: PsiElement): T =
    PsiTreeUtil
      .findChildrenOfType(element, implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]])
      .asScala
      .toVector
      .headOption
      .getOrElse(
        throw new AssertionError(
          s"no ${implicitly[ClassTag[T]].runtimeClass.getSimpleName} inside '${element.getText}'"
        )
      )

  private def astLeaves(element: PsiElement): Vector[PsiElement] =
    PsiTreeUtil
      .findChildrenOfType(element, classOf[PsiElement])
      .asScala
      .toVector
      .filter(child => child.getFirstChild == null && child.getLastChild == null)

  private def simpleTypes(file: PsiFile): Vector[ScSimpleTypeElementImpl] =
    descendants[ScSimpleTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)

  private def projectionElems(file: PsiFile): Vector[ScTypeProjectionImpl] =
    descendants[ScTypeProjectionImpl](file).sortBy(_.getTextRange.getStartOffset)

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
  def testMatchOwnedDottedPathsProduceNativePsi(): Unit =
    val source  =
      """def dotted(x: Any): Any = x match
        |  case y: a.B => "two"
        |  case y: pkg.a.B => "three"
        |  case y: a.b.c.d.T => "deep"
        |""".stripMargin
    val file    = physical("MatchStableSelectDot.scala", source)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)
    val simples = simpleTypes(file).filter(_.getText.contains("."))
    assertEquals(3, simples.size)
    assertEquals(Vector("a.B", "pkg.a.B", "a.b.c.d.T"), simples.map(_.getText))
    simples.foreach: simple =>
      assertTrue(simple.isInstanceOf[ScSimpleTypeElementImpl])
      val reference   = simple.reference
      assertTrue(s"reference missing for '${simple.getText}'", reference.isDefined)
      val ref         = reference.get
      assertTrue(ref.isInstanceOf[ScStableCodeReferenceImpl])
      assertSame(simple, ref.getParent)
      assertEquals(simple.getText, ref.getText)
      val path        = simple.pathElement
      assertEquals(s"pathElement text for '${simple.getText}'", ref.getText, path.getText)
      assertFalse(simple.isSingleton)
      val nameId      = ref.nameId
      assertEquals(simple.getText.split('.').last, nameId.getText)
      val qualifier   = ref.qualifier
      assertTrue(s"qualifier missing for '${simple.getText}'", qualifier.isDefined)
      val qual        = qualifier.get
      assertTrue(
        s"qualifier of '${simple.getText}' must be a stable reference, was ${qual.getClass.getSimpleName}",
        qual.isInstanceOf[ScStableCodeReferenceImpl] && !qual.isInstanceOf[ScSimpleTypeElementImpl]
      )
      assertSame(ref, qual.getParent)
      val dotLeaves   = astLeaves(ref).filter(_.getText == ".")
      val segmentDots = simple.getText.count(_ == '.')
      assertEquals(s"dot leaves for '${simple.getText}'", segmentDots, dotLeaves.size)
      dotLeaves.foreach(dot => assertTrue(ref.getTextRange.contains(dot.getTextRange)))
      var current     = qualifier
      var depth       = 0
      var ended       = false
      while depth < 8 && !ended do
        current match
          case Some(value: ScStableCodeReferenceImpl) =>
            current = value.qualifier
          case other                                  =>
            assertTrue(s"qualifier chain must end in a leaf identifier: $other", other.isEmpty)
            ended = true
        depth += 1
      assertTrue(s"qualifier chain of '${simple.getText}' must terminate within 8 levels", ended)
      val deep        = simples.last.reference.get
      assertEquals("T", deep.nameId.getText)
      assertEquals("d", deep.qualifier.get.nameId.getText)
      assertEquals("a", deep.qualifier.get.qualifier.get.qualifier.get.qualifier.get.nameId.getText)
      simple.getParent match
        case wrapper: ScTypePattern =>
          assertEquals(simple.getTextRange, wrapper.getTextRange)
        case other                  =>
          fail(s"unexpected dotted-type parent: ${other.getClass.getName}")
      assertPhysicalContract(simple, source)
    val refs    = descendants[ScStableCodeReferenceImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertTrue(refs.size >= 3)

  @Test
  def testMatchOwnedHashProjectionsProduceNativePsi(): Unit =
    val source           =
      """def hashes(x: Any): Any = x match
        |  case y: Outer#T => "direct"
        |  case y: pkg.Outer#T => "qualified"
        |  case y: Outer#T#U => "left-assoc"
        |""".stripMargin
    val file             = physical("MatchStableSelectHash.scala", source)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)
    val projections      = projectionElems(file)
    assertEquals(4, projections.size)
    assertEquals(Vector("Outer#T", "pkg.Outer#T", "Outer#T#U", "Outer#T"), projections.map(_.getText))
    val outerProjections = projections.filterNot(projection =>
      projection.getParent.isInstanceOf[ScTypeProjectionImpl] ||
        projection.getParent.isInstanceOf[ScSimpleTypeElementImpl]
    )
    assertEquals(Vector("Outer#T", "pkg.Outer#T", "Outer#T#U"), outerProjections.map(_.getText))
    outerProjections.foreach: projection =>
      assertTrue(projection.isInstanceOf[ScTypeProjectionImpl])
      projection.getParent match
        case wrapper: ScTypePattern =>
          assertEquals(projection.getTextRange, wrapper.getTextRange)
        case other                  =>
          fail(s"unexpected outer projection parent: ${other.getClass.getName}")
      val left         = projection.typeElement
      val expectedLeft = projection.getText.substring(0, projection.getText.lastIndexOf('#'))
      assertEquals(expectedLeft, left.getText)
      assertEquals(projection.getText.split('#').last, projection.nameId.getText)
      assertTrue(s"hash leaf missing in '${projection.getText}'", astLeaves(projection).exists(_.getText == "#"))
      assertTrue(
        s"projection qualifier must stay unexposed for '${projection.getText}': ${projection.qualifier}",
        projection.qualifier.isEmpty
      )
    val leftAssoc        = outerProjections.last
    assertEquals("U", leftAssoc.nameId.getText)
    assertEquals("Outer#T", leftAssoc.typeElement.getText)
    val innerProjection  = astDescendant[ScTypeProjectionImpl](leftAssoc.typeElement)
    assertEquals("T", innerProjection.nameId.getText)
    assertEquals("Outer", innerProjection.typeElement.getText)
    projections.foreach(projection => assertPhysicalContract(projection, source))
    assertEquals(1, descendants[ScMatchImpl](file).size)

  @Test
  def testMatchOwnedStableSelectsComposeAcrossTypePositions(): Unit =
    val source      =
      """def composed(x: Any): Any = x match
        |  case y: a.F[Int] => "applied-constructor"
        |  case y: Outer#F[Int] => "applied-hash"
        |  case y: F[a.B] => "applied-arg"
        |  case y: (a.B, Outer#T) => "tuple"
        |  case y: Box[? <: a.B] => "wildcard"
        |  case given a.B => "given"
        |  case o @ given a.B => "given-named"
        |""".stripMargin
    val file        = physical("MatchStableSelectComposed.scala", source)
    assertEquals(7, descendants[ScCaseClauseImpl](file).size)
    val simples     = simpleTypes(file)
    assertTrue(simples.nonEmpty)
    val projections = projectionElems(file)
    assertTrue(projections.nonEmpty)
    assertEquals(1, descendants[ScMatchImpl](file).size)

  @Test
  def testDottedAndTupleTypesCoexistAcrossCaseClauses(): Unit =
    val source =
      """def pending(x: Any): Any = x match
        |  case y: a.B => 1
        |  case z: (Int, String) => 2
        |""".stripMargin
    val file   = physical("MatchStableSelectTupleMix.scala", source)
    assertEquals(2, descendants[ScCaseClauseImpl](file).size)
    val dotted = simpleTypes(file).filter(_.getText.contains("."))
    assertEquals(Vector("a.B"), dotted.map(_.getText))
    assertEquals(2, descendants[Sc3TypedPatternImpl](file).size)

  @Test
  def testStableSelectsCloseOverAllWildcardBoundStates(): Unit =
    val source      =
      """def pending(x: Any): Any = x match
        |  case l: Box[? >: a.B] => "lower"
        |  case u: Box[? <: pkg.A.B] => "upper"
        |  case b: Box[? >: a.B <: Outer#T] => "both"
        |""".stripMargin
    val file        = physical("MatchStableSelectBoundStates.scala", source)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)
    val dotted      = simpleTypes(file).filter(_.getText.contains(".")).map(_.getText)
    assertEquals(Vector("a.B", "pkg.A.B", "a.B"), dotted)
    val projections = projectionElems(file).map(_.getText)
    assertEquals(Vector("Outer#T"), projections)

  @Test
  def testBacktickedAndGivenWrappedStableSelectsStayAdmitted(): Unit =
    val source =
      """def pending(x: Any): Any = x match
        |  case given pkg.`type` => 1
        |  case o @ given a.B => 2
        |  case y: pkg.`type`.T => 3
        |""".stripMargin
    val file   = physical("MatchStableSelectBackticks.scala", source)
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    assertEquals(2, descendants[ScGivenPatternImpl](file).size)

  @Test
  def testStableSelectTriviaStaysInsideTheEmission(): Unit =
    val source =
      """def pending(x: Any): Any = x match
        |  case y: a. /* mid */ B => 1
        |""".stripMargin
    val file   = physical("MatchStableSelectTrivia.scala", source)
    assertEquals(1, descendants[ScCaseClauseImpl](file).size)
    val dotted = simpleTypes(file).filter(_.getText.contains("."))
    assertEquals(1, dotted.size)
    val ref    = dotted.head.reference.get
    assertEquals(1, astLeaves(ref).count(_.getText == "."))
    assertTrue(
      s"comment trivia must stay inside '${dotted.head.getText}'",
      dotted.head.getText.contains("/* mid */")
    )

  @Test
  def testMatchOwnedStableSelectsStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source   =
      """def persisted(x: Any): Any = x match
        |  case y: a.B => "dotted"
        |  case y: Outer#T => "hash"
        |""".stripMargin
    val file     = physical("MatchStableSelectPersisted.scala", source)
    val fileInfo = file.asInstanceOf[PsiFileImpl]
    val stubTree = fileInfo.calcStubTree
    val stubList = stubTree.getPlainList.asScala.toVector
    assertTrue(
      "stable selects must not create stubs",
      stubList.forall(stub => !stub.getClass.getSimpleName.toLowerCase.contains("stableselect"))
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
    val reparsed = simpleTypes(file).filter(_.getText.contains("."))
    assertEquals(Vector("a.B"), reparsed.map(_.getText))

  @Test
  def testMatchOwnedStableSelectEditsTransitionBetweenAdmittedAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: TYPE => "t"
        |""".stripMargin
    val file                               = physical("MatchStableSelectEdit.scala", template.replace("TYPE", "a.B"))
    assertEquals(Vector("a.B"), simpleTypes(file).filter(_.getText.contains(".")).map(_.getText))
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("TYPE", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("Outer#T")
    assertEquals(Vector("Outer"), simpleTypes(file).map(_.getText).filterNot(_ == "Any"))
    assertEquals(Vector("Outer#T"), projectionElems(file).map(_.getText))
    replace("a.B")
    assertEquals(Vector("a.B"), simpleTypes(file).filter(_.getText.contains(".")).map(_.getText))
    assertTrue(projectionElems(file).isEmpty)
    replace("Outer[Int]#T")
    assertTrue(simpleTypes(file).isEmpty)
    assertTrue(projectionElems(file).isEmpty)

  @Test
  def testMatchOwnedStableSelectFailClosedShapesStayUncoveredWithoutPartialPsi(): Unit =
    val sources = Vector(
      """def pending(x: Any): Any = x match
        |  case y: Outer#T.U => "dot-after-hash"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: Outer[Int]#T => "applied-qualifier"
        |""".stripMargin,
      """def partial(x: Any): Any = { case y: a.B => 1 }""",
      """def catcher(x: Any): Any = try x catch { case y: a.B => 1 }""",
      """def gen(xs: List[Any]): List[Any] = for (y: a.B) <- xs yield y""",
      """def quoted(x: Any): Any = '{ case y: a.B => 1 }"""
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchStableSelectUnsupported$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported stable-select shape should fail closed (source $index)", failure.isDefined)
      assertTrue(simpleTypes(file).isEmpty)
      assertTrue(projectionElems(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    }

  @Test
  def testFileScopedStableSelectsStayOutsideMatchOwnership(): Unit =
    val source  =
      """class C:
        |  def m(v: a.B): v.type = v
        |  def n(w: Outer#T): Outer#T = w
        |""".stripMargin
    val file    = pendingFile("MatchStableSelectFileScope.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"file-scoped stable selects keep the installed parse: $failure", failure.isEmpty)
    assertEquals(
      Vector("a.B", "v.type", "Outer", "Outer"),
      simpleTypes(file).map(_.getText)
    )
    assertEquals(Vector("Outer#T", "Outer#T"), projectionElems(file).map(_.getText))
    assertTrue(descendants[ScMatchImpl](file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)

  @Test
  def testMixedAdmittedAndUnsupportedCaseClausesFallBackAtomically(): Unit =
    val source  =
      """def pending(x: Any): Any = x match
        |  case y: a.B => 1
        |  case z: Outer[Int]#T => 2
        |""".stripMargin
    val file    = pendingFile("MatchStableSelectMixedUnsupported.scala", source)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(s"mixed unsupported shape must fail closed atomically: $failure", failure.isDefined)
    assertTrue(simpleTypes(file).filter(_.getText.contains(".")).isEmpty)
    assertTrue(projectionElems(file).isEmpty)
    assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)

  @Test
  def testStableSelectPsiSurvivesCopiesPointersAndReload(): Unit =
    val source            =
      """def pending(x: Any): Any = x match
        |  case y: a.B => "dotted"
        |  case z: Outer#T => "hash"
        |""".stripMargin
    val file              = physical("MatchStableSelectCopy.scala", source)
    val dotted            = simpleTypes(file).filter(_.getText.contains("."))
    assertEquals(Vector("a.B"), dotted.map(_.getText))
    val projection        = projectionElems(file).head
    assertEquals("Outer#T", projection.getText)
    val copied            = file.copy().asInstanceOf[PsiFile]
    assertEquals(source, copied.getText)
    assertEquals(Vector("a.B"), simpleTypes(copied).filter(_.getText.contains(".")).map(_.getText))
    assertEquals(Vector("Outer#T"), projectionElems(copied).map(_.getText))
    val manager           = com.intellij.psi.SmartPointerManager.getInstance(getProject)
    val pointer           = manager.createSmartPsiElementPointer(dotted.head)
    val bound             = pointer.getElement
    assertNotNull(bound)
    assertEquals("a.B", bound.getText)
    val projectionPointer = manager.createSmartPsiElementPointer(projection)
    assertEquals("Outer#T", projectionPointer.getElement.getText)
    val fileInfo          = file.asInstanceOf[PsiFileImpl]
    fileInfo.setTreeElementPointer(null)
    assertEquals(null, fileInfo.getTreeElement)
    assertEquals("a.B", pointer.getElement.getText)
    assertEquals("Outer#T", projectionPointer.getElement.getText)
    assertEquals(Vector("a.B"), simpleTypes(file).filter(_.getText.contains(".")).map(_.getText))

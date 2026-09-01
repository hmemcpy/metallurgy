package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{SerializationManagerEx, StubTree, PsiFileStub}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScNamedTupleTypeElement
import org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.{
  ScCaseClauseImpl,
  ScGivenPatternImpl,
  Sc3TypedPatternImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.{
  ScParameterizedTypeElementImpl,
  ScTypeArgsImpl,
  ScTypesImpl,
  ScTupleTypeElementImpl
}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMatchImpl
import org.junit.Assert.*
import org.junit.Test

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3MatchPatternTupleTypePsiTest extends Scala3CompatTestCase:
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

  private def tupleTypeElements(file: PsiFile): Vector[ScTupleTypeElementImpl] =
    descendants[ScTupleTypeElementImpl](file).sortBy(_.getTextRange.getStartOffset)

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
  def testQuoteCaseBlocksAscriptionsAndDoubleParensStayFailClosedWithoutTuplePsi(): Unit =
    val sources = Vector(
      """def quoted(x: Any): Any = '{ case y: (Int, String) => 1 }""",
      """def pending(x: Any): Any = x match
        |  case _ => v: (Int, String)
        |""".stripMargin,
      """def pending(x: Any): Any = (x: (Int, String)) match
        |  case _ => 1
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case given ((Int, String)) => 1
        |""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchTupleStructuralExclusion$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"structurally excluded tuple-type shape should fail closed (source $index)", failure.isDefined)
      assertTrue(tupleTypeElements(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScGivenPatternImpl](file).isEmpty)
    }

  @Test
  def testMatchScopedTupleTypesProduceNativePsiAcrossTheAdmittedMatrix(): Unit =
    val source  =
      """def shapes(x: Any): Any = x match
        |  case y: (Int, String) => "direct"
        |  case y: (Box[Int], (Int, String)) => "nested"
        |  case y: Box[(Int, String)] => "applied"
        |  case y: (Int, String) if n > 0 => "guarded"
        |  case _: (Int, String) => "wildcard"
        |  case (p: (Int, String), q) => "composed"
        |""".stripMargin
    val file    = physical("MatchTupleTypes1.scala", source)
    assertEquals(6, descendants[ScCaseClauseImpl](file).size)
    val typed   = descendants[Sc3TypedPatternImpl](file)
    assertEquals(6, typed.size)
    val tuples  = tupleTypeElements(file)
    assertEquals(
      Vector(
        "(Int, String)",
        "(Box[Int], (Int, String))",
        "(Int, String)",
        "(Int, String)",
        "(Int, String)",
        "(Int, String)",
        "(Int, String)"
      ),
      tuples.map(_.getText)
    )
    val nested  = tuples(1)
    assertEquals(
      Vector("Box[Int]", "(Int, String)"),
      nested.components.map(_.getText)
    )
    assertSame(nested, nested.typeList.getParent)
    assertEquals(nested.components.toVector, nested.typeList.types.toVector)
    nested.components.foreach(component => assertSame(nested.typeList, component.getParent))
    tuples.foreach: tuple =>
      val start = tuple.getTextRange.getStartOffset
      val end   = tuple.getTextRange.getEndOffset
      assertEquals('(', file.getText.charAt(start))
      assertEquals(')', file.getText.charAt(end - 1))
      tuple.getParent match
        case wrapper: ScTypePattern =>
          assertEquals(tuple.getTextRange, wrapper.getTextRange)
        case typesList: ScTypesImpl =>
          assertTrue(
            s"component tuple must sit inside its parent component list: ${typesList.getText}",
            typesList.getText.contains(tuple.getText)
          )
        case args: ScTypeArgsImpl   =>
          assertTrue(
            s"component tuple must sit inside its applied arguments: ${args.getText}",
            args.getText.contains(tuple.getText)
          )
        case other                  =>
          fail(s"unexpected tuple-type parent in typed pattern: ${other.getClass.getName}")
      assertTrue(tuple.isInstanceOf[ScTupleTypeElementImpl])
    val applied = descendants[ScParameterizedTypeElementImpl](file).map(_.getText)
    assertTrue(applied.contains("Box[(Int, String)]"))
    assertTrue(applied.contains("Box[Int]"))
    assertEquals(1, descendants[ScMatchImpl](file).size)
    tupleTypeElements(file).foreach(tuple => assertPhysicalContract(tuple, source))

  @Test
  def testMatchScopedTupleTypesOwnGivenPatternTypePositions(): Unit =
    val source =
      """def givens(x: Any): Any = x match
        |  case given (Int, String) => "anon"
        |  case ord @ given (Box[Int], (Int, String)) => "named"
        |  case given Box[(Int, String)] => "applied"
        |""".stripMargin
    val file   = physical("MatchTupleTypesGiven.scala", source)
    val givens = descendants[ScGivenPatternImpl](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(3, givens.size)
    assertEquals(
      Vector("given (Int, String)", "given (Box[Int], (Int, String))", "given Box[(Int, String)]"),
      givens.map(_.getText)
    )
    val tuples = tupleTypeElements(file)
    assertEquals(
      Vector("(Int, String)", "(Box[Int], (Int, String))", "(Int, String)", "(Int, String)"),
      tuples.map(_.getText)
    )
    val nested = tuples(1)
    assertEquals(Vector("Box[Int]", "(Int, String)"), nested.components.map(_.getText))
    assertEquals(3, descendants[ScCaseClauseImpl](file).size)

  @Test
  def testMatchScopedTupleTypesRetainDepthWidthOrderAndRanges(): Unit =
    val deep     = (1 to 16).foldRight("Tail")((_, acc) => s"($acc, Head)")
    val wide     = (1 to 32).map(i => s"T$i").mkString(", ")
    val source   =
      s"""def limits(x: Any): Any = x match
         |  case y: $deep => "deep"
         |  case y: ($wide) => "wide"
         |""".stripMargin
    val file     = physical("MatchTupleTypesLimits.scala", source)
    val tuples   = tupleTypeElements(file)
    assertEquals(17, tuples.size)
    val deepRoot = tuples.head
    assertEquals(deep, deepRoot.getText)
    var level    = deepRoot
    var depth    = 1
    while level.components.exists(_.isInstanceOf[ScTupleTypeElementImpl]) do
      val inner = level.components.collectFirst { case nested: ScTupleTypeElementImpl => nested }.get
      assertSame(level.typeList, inner.getParent)
      assertEquals(2, level.components.size)
      level = inner
      depth += 1
    assertEquals(16, depth)
    assertEquals(Vector("Tail", "Head"), level.components.map(_.getText))
    val wideRoot = tuples.find(_.getText == s"($wide)").get
    assertEquals(32, wideRoot.components.size)
    assertEquals(
      (1 to 32).map(i => s"T$i"),
      wideRoot.components.map(_.getText)
    )
    assertSame(wideRoot, wideRoot.typeList.getParent)
    assertEquals(wideRoot.components.toVector, wideRoot.typeList.types.toVector)

  @Test
  def testMatchScopedTupleTypesStayAstOnlyAcrossSerializationReloadAndReparse(): Unit =
    val source   =
      """def persisted(x: Any): Any = x match
        |  case y: (Int, String) => "tuple"
        |  case y: Box[(Int, String)] => "applied"
        |""".stripMargin
    val file     = physical("MatchTupleTypesPersisted.scala", source)
    val fileInfo = file.asInstanceOf[PsiFileImpl]
    val stubTree = fileInfo.calcStubTree
    val stubList = stubTree.getPlainList.asScala.toVector
    assertTrue(
      "tuple types must not create stubs",
      stubList.forall(stub => !stub.getClass.getSimpleName.toLowerCase.contains("tupletype"))
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
    val reparsed = tupleTypeElements(file)
    assertEquals(Vector("(Int, String)", "(Int, String)"), reparsed.map(_.getText))
    reparsed.foreach: tuple =>
      assertTrue(tuple.isInstanceOf[ScTupleTypeElementImpl])
      assertEquals(2, tuple.components.size)

  @Test
  def testMatchScopedTupleTypeEditsTransitionBetweenAdmittedAndFailClosed(): Unit =
    val template                           =
      """def transitions(x: Any): Any = x match
        |  case y: TUPLETYPE => "t"
        |""".stripMargin
    val file                               = physical("MatchTupleTypesEdit.scala", template.replace("TUPLETYPE", "(Int, String)"))
    assertEquals(Vector("(Int, String)"), tupleTypeElements(file).map(_.getText))
    val document                           = PsiDocumentManager.getInstance(getProject).getDocument(file)
    def replace(replacement: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.replaceString(0, document.getTextLength, template.replace("TUPLETYPE", replacement))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    replace("(Int)")
    assertTrue(tupleTypeElements(file).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    replace("(Int, String,)")
    assertTrue(tupleTypeElements(file).isEmpty)
    replace("(value: Int, other: String)")
    assertTrue(tupleTypeElements(file).isEmpty)
    replace("(Box[Int], (Int, String))")
    assertEquals(
      Vector("(Box[Int], (Int, String))", "(Int, String)"),
      tupleTypeElements(file).map(_.getText)
    )
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)
    replace("(Int, String)")
    assertEquals(Vector("(Int, String)"), tupleTypeElements(file).map(_.getText))
    assertEquals(1, descendants[Sc3TypedPatternImpl](file).size)

  @Test
  def testMatchScopedTupleTypeFailClosedShapesStayUncoveredWithoutPartialPsi(): Unit =
    val parsableSources = Vector(
      """def pending(x: Any): Any = x match
        |  case y: (Int) => "paren"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: (a: Int, b: String) => "named"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: ((Int, String) => Boolean) => "fn"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: a.B => "qualified"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: Box[? <: Int] => "bounds"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: Any { type X } => "refine"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: (Int, String) @unchecked => "annot"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: x.type => "singleton"
        |""".stripMargin
    )
    parsableSources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchTupleUnsupported$index.scala", source)
      assertTrue(
        s"unsupported tuple-type fixture must parse without errors (source $index)",
        PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty
      )
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"unsupported tuple-type shape should fail closed (source $index)", failure.isDefined)
      assertTrue(
        s"failure should mention uncovered shape (source $index): ${failure.get}",
        failure.get.toString.contains("UncoveredCompilerShape")
      )
      assertTrue(tupleTypeElements(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScNamedTupleTypeElement](file).isEmpty)
    }
    val nonParseSources = Vector(
      """def pending(x: Any): Any = x match
        |  case y: ((Int, String)) => "double"
        |""".stripMargin,
      """def pending(x: Any): Any = x match
        |  case y: (Int, String,) => "trailing"
        |""".stripMargin
    )
    nonParseSources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchTupleNonParse$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"parser-rejected tuple-type shape should fail closed (source $index)", failure.isDefined)
      assertTrue(tupleTypeElements(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
    }
    val unitSource      =
      """def pending(x: Any): Any = x match
        |  case y: () => "unit"
        |""".stripMargin
    val unitFile        = pendingFile("MatchTupleUnsupportedUnit.scala", unitSource)
    val unitFailure     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(unitFile.getVirtualFile, ParserSyntaxSnapshot.digest(unitSource))
    assertTrue(s"unit-typed pattern is a parser rejection and must fail closed: $unitFailure", unitFailure.isDefined)
    assertTrue(tupleTypeElements(unitFile).isEmpty)
    assertTrue(descendants[Sc3TypedPatternImpl](unitFile).isEmpty)

  @Test
  def testMatchScopedTupleTypesStayFailClosedOutsideRealMatchOwnership(): Unit =
    val sources = Vector(
      """def partial(x: Any): Any = { case y: (Int, String) => "pf" }""",
      """def partial: Any => String = case y: (Int, String) => "pf" """.stripMargin,
      """def catcher(x: Any): Any = try x catch { case y: (Int, String) => "catch" }""",
      """def catcher(x: Any): Any = try x catch case y: (Int, String) => "catch" """.stripMargin,
      """def gen(xs: List[Any]): List[Any] = for (y: (Int, String)) <- xs yield y""".stripMargin
    )
    sources.zipWithIndex.foreach { case (source, index) =>
      val file    = pendingFile(s"MatchTupleContext$index.scala", source)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"non-match tuple-type context should fail closed (source $index)", failure.isDefined)
      assertTrue(tupleTypeElements(file).isEmpty)
      assertTrue(descendants[Sc3TypedPatternImpl](file).isEmpty)
      assertTrue(descendants[ScCaseClauseImpl](file).isEmpty)
    }

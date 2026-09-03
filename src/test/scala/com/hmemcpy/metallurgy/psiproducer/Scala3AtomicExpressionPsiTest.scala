package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{
  IndexSink,
  ObjectStubSerializer,
  PsiFileStub,
  SerializationManagerEx,
  Stub,
  StubIndexKey,
  StubTree
}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.*
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScExpression,
  ScGenericCall,
  ScReferenceExpression,
  ScThisReference
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunctionDefinition,
  ScPatternDefinition,
  ScVariableDefinition
}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3AtomicExpressionPsiTest extends Scala3CompatTestCase:
  def testDirectDefinitionRhsAtomsExposeCompleteNativePsi(): Unit =
    val source      =
      """val reference = source
        |val integer = 0x7f_ff
        |var long = 9_223L
        |def float = 1.25f
        |val double = 2.5d
        |val boolTrue = true
        |val boolFalse = false
        |val char = '\n'
        |val string = "a\tb"
        |val nil = null
        |class C:
        |  def plainThis = this
        |  val qualifiedThis = C.this
        |""".stripMargin
    val file        = physical("AtomicExpressions1.scala", source)
    val expressions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)

    assertEquals(
      Vector(
        "source",
        "0x7f_ff",
        "9_223L",
        "1.25f",
        "2.5d",
        "true",
        "false",
        "'\\n'",
        "\"a\\tb\"",
        "null",
        "this",
        "C.this"
      ),
      expressions.map(_.getText)
    )
    expressions.foreach: expression =>
      assertEquals(expression.getText, expression.getTextRange.substring(source))
      assertSame(expression, expression.getNode.getPsi)
      assertTrue(
        expression.getParent.isInstanceOf[ScFunctionDefinition] ||
          expression.getParent.isInstanceOf[ScPatternDefinition] ||
          expression.getParent.isInstanceOf[ScVariableDefinition]
      )
      assertEquals(Vector(expression), ownerExpression(expression.getParent))

    val reference = expressions.head.asInstanceOf[ScReferenceExpression]
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScReferenceExpressionImpl", reference.getClass.getName)
    assertEquals("source", reference.refName)
    assertEquals("source", reference.nameId.getText)
    assertSame(reference, reference.nameId.getParent)
    assertTrue(reference.qualifier.isEmpty)
    assertEquals(Vector("source"), astChildren(reference))
    assertTrue(reference.`type`().isLeft)

    val expectedLiterals = Vector(
      (classOf[ScIntegerLiteral], Integer.valueOf(32767), "0x7f_ff", new TextRange(37, 44), new TextRange(0, 7), true),
      (
        classOf[ScLongLiteral],
        java.lang.Long.valueOf(9223L),
        "9_223L",
        new TextRange(56, 62),
        new TextRange(0, 6),
        true
      ),
      (
        classOf[ScFloatLiteral],
        java.lang.Float.valueOf(1.25f),
        "1.25f",
        new TextRange(75, 80),
        new TextRange(0, 5),
        true
      ),
      (
        classOf[ScDoubleLiteral],
        java.lang.Double.valueOf(2.5d),
        "2.5d",
        new TextRange(94, 98),
        new TextRange(0, 4),
        true
      ),
      (classOf[ScBooleanLiteral], java.lang.Boolean.TRUE, "true", new TextRange(114, 118), new TextRange(0, 4), true),
      (classOf[ScBooleanLiteral], java.lang.Boolean.FALSE, "false", new TextRange(135, 140), new TextRange(0, 5), true),
      (
        classOf[ScCharLiteral],
        java.lang.Character.valueOf('\n'),
        "\\n",
        new TextRange(153, 155),
        new TextRange(1, 3),
        true
      ),
      (classOf[ScStringLiteral], "a\tb", "a\\tb", new TextRange(171, 175), new TextRange(1, 5), true),
      (classOf[ScNullLiteral], null, "null", new TextRange(187, 191), new TextRange(0, 4), false)
    )
    val literals         = expressions.collect { case literal: ScLiteral => literal }
    expectedLiterals
      .zip(literals)
      .foreach: (expected, literal) =>
        val (apiClass, value, content, contentRange, contentRangeInParent, simple) = expected
        assertTrue(literal.getClass.getName, apiClass.isInstance(literal))
        assertEquals(value, literal.getValue)
        assertEquals(content, literal.contentText)
        assertEquals(contentRange, literal.contentRange)
        assertEquals(contentRangeInParent, literal.contentRangeInParent)
        assertEquals(simple, literal.isSimpleLiteral)
        assertEquals(Vector(literal.getText), astChildren(literal))
        assertTrue(literal.literalType != null)
        assertEquals(literal.literalType, literal.`type`().toOption.get)

    val thisReferences = expressions.collect { case value: ScThisReference => value }
    assertEquals(Vector("this", "C.this"), thisReferences.map(_.getText))
    assertTrue(thisReferences.head.reference.isEmpty)
    assertTrue(thisReferences.head.refTemplate.nonEmpty)
    val qualifier      = thisReferences.last.reference.get
    assertEquals("C", qualifier.refName)
    assertEquals("C", qualifier.nameId.getText)
    assertSame(thisReferences.last, qualifier.getParent)
    assertTrue(thisReferences.last.refTemplate.nonEmpty)
    assertEquals(Vector("C", ".", "this"), astChildren(thisReferences.last))
    thisReferences.foreach(reference => assertTrue(reference.`type`().isRight))

    val stubClasses = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.map(_.getClass.getName).toVector
    assertFalse(stubClasses.exists(name => name.contains("Expression") || name.contains("Literal")))

  def testAtomsOutsideCompleteDirectDefinitionRhsRemainOpaquePayloads(): Unit =
    val source   =
      """def local =
        |    val nested = source
        |    nested
        |val tupled = (source, 1)
        |val negative = -1
        |""".stripMargin
    val file     = physical("AtomicExpressions2.scala", source)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScThisReference]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScLiteral]).isEmpty)
    val payloads = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
    Vector("source", "(source, 1)", "-1")
      .foreach(text => assertTrue(text, payloads.exists(_.getText == text)))
    assertTrue(
      payloads.forall(payload =>
        PsiTreeUtil.findChildrenOfType(payload, classOf[ScReferenceExpression]).isEmpty &&
          PsiTreeUtil.findChildrenOfType(payload, classOf[ScThisReference]).isEmpty &&
          PsiTreeUtil.findChildrenOfType(payload, classOf[ScLiteral]).isEmpty
      )
    )

  def testMixedDirectAndExcludedOwnersDoNotAdmitAtomsThroughDistantAncestry(): Unit =
    val source     =
      """val direct = source
        |type Refined = AnyRef { val member: Int }
        |def outer = { val local = source; local }
        |""".stripMargin
    val file       = physical("AtomicExpressionsExcludedOwners.scala", source)
    val references = PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).asScala.toVector
    assertEquals(Vector("source"), references.map(_.getText))
    assertTrue(references.head.getParent.isInstanceOf[ScPatternDefinition])
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScThisReference]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScLiteral]).isEmpty)
    val payloads   = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
    Vector("source")
      .foreach(text => assertTrue(text, payloads.exists(_.getText == text)))
    assertTrue(
      payloads.forall(payload =>
        PsiTreeUtil.findChildrenOfType(payload, classOf[ScReferenceExpression]).isEmpty &&
          PsiTreeUtil.findChildrenOfType(payload, classOf[ScThisReference]).isEmpty &&
          PsiTreeUtil.findChildrenOfType(payload, classOf[ScLiteral]).isEmpty
      )
    )

  def testPositionalTypeApplicationIsNativeWhileExcludedExpressionRootsRemainCompletePayloads(): Unit =
    val positionalSource = "val typed = source[Int]\n"
    val positionalFile   = physical("AtomicBoundary1.scala", positionalSource)
    val positional       = PsiTreeUtil.findChildOfType(positionalFile, classOf[ScGenericCall])
    assertEquals("source[Int]", positional.getText)
    assertEquals("source", positional.referencedExpr.getText)
    assertEquals("[Int]", positional.typeArgs.getText)
    assertSame(positional, positional.referencedExpr.getParent)
    assertSame(positional, positional.typeArgs.getParent)
    assertTrue(PsiTreeUtil.findChildrenOfType(positionalFile, classOf[MetallurgyExpressionPayload]).isEmpty)

    val sources = Vector(
      "val tupled = (source, 1)\n",
      "val blocked = { source }\n",
      "val infixed = source + 1\n",
      "val negative = -1\n"
    )
    sources.zipWithIndex.foreach: (source, index) =>
      val file     = physical(s"AtomicBoundary${index + 2}.scala", source)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScThisReference]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScLiteral]).isEmpty)
      val payloads = PsiTreeUtil
        .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
        .asScala
        .toVector
      val rhsStart = source.indexOf('=') + 2
      val rhsEnd   = source.lastIndexWhere(character => !character.isWhitespace) + 1
      assertEquals(source, 1, payloads.count(_.getTextRange == TextRange(rhsStart, rhsEnd)))
      assertEquals(source, payloads.size, payloads.map(_.getTextRange).distinct.size)
      payloads.foreach: payload =>
        assertTrue(
          source,
          PsiTreeUtil
            .findChildrenOfType(payload, classOf[ScExpression])
            .asScala
            .forall(_.isInstanceOf[MetallurgyExpressionPayload])
        )

  def testDirectAtomsSurviveCopyEditAndReparse(): Unit =
    val source    = "val value = source\n"
    val file      = physical("AtomicExpressions3.scala", source)
    val reference = PsiTreeUtil.findChildOfType(file, classOf[ScReferenceExpression])
    val pointer   = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(reference)
    assertEquals("source", PsiTreeUtil.findChildOfType(file.copy(), classOf[ScReferenceExpression]).getText)
    val document  = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(source.indexOf("source"), source.indexOf("source") + 6, "42L")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertTrue(pointer.getElement == null)
    val literal   = PsiTreeUtil.findChildOfType(file, classOf[ScLongLiteral])
    assertEquals("42L", literal.getText)
    assertEquals(java.lang.Long.valueOf(42L), literal.getValue)
    assertEquals("42L", PsiTreeUtil.findChildOfType(file.copy(), classOf[ScLongLiteral]).getText)

  def testAtomicExpressionsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    assertEquals(15, Scala3DotcFileElementType.SchemaVersion)
    assertEquals(
      "2f23b108abf74ea05bdf3de86b97dace63c2c72c74589f3921c21a7054c69112",
      Scala3DotcFileElementType.PersistenceSchemaFingerprint
    )
    val source      = "package atoms\nval stable = source\nclass C { val self = C.this }\n"
    val file        = physical("AtomicPersistence1.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val beforeShape = stubShape(stubs)
    val beforeIndex = indexShape(stubs)
    assertFalse(
      beforeShape.exists(row => row.contains("Expression") || row.contains("Literal") || row.contains("This"))
    )
    assertEquals(
      Vector("source", "C.this"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).asScala.map(_.getText).toVector
    )

    val output   = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes    = output.toByteArray
    val restored = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(beforeShape, stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    val repeated = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)

    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(beforeShape, stubShape(file.getStubTree.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala))
    assertEquals(
      Vector("source", "C.this"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).asScala.map(_.getText).toVector
    )
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .foreach(expression => assertSame(expression, expression.getNavigationElement))

  def testMalformedAtomicRhsFailsClosedAndReplacementDeletionRemainDeterministic(): Unit =
    val malformed = "val broken = C.\n"
    val pending   = myFixture.addFileToProject("src/AtomicMalformed1.scala", malformed)
    val broken    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    broken.getChildren
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(malformed))
        .nonEmpty
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(broken, classOf[ScExpression]).isEmpty)

    val source   = "val value = source\n"
    val file     = physical("AtomicReplacement1.scala", source)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.deleteString(source.indexOf("source"), source.indexOf("source") + 6)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).isEmpty)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(document.getTextLength - 1, "null")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("null", PsiTreeUtil.findChildOfType(file, classOf[ScNullLiteral]).getText)

  private def ownerExpression(owner: com.intellij.psi.PsiElement): Vector[ScExpression] = owner match
    case function: ScFunctionDefinition => function.body.toVector
    case value: ScPatternDefinition     => value.expr.toVector
    case variable: ScVariableDefinition => variable.expr.toVector

  private def astChildren(element: com.intellij.psi.PsiElement): Vector[String] =
    element.getNode.getChildren(null).toVector.map(_.getText)

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

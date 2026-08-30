package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
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
import com.intellij.util.FileContentUtilCore
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{ScIntegerLiteral, ScStringLiteral}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScArgumentExprList,
  ScAssignment,
  ScExpression,
  ScMethodCall,
  ScReferenceExpression
}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{MetallurgyExpressionPayload, MetallurgyNamedArgument}
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3NamedArgumentPsiTest extends Scala3CompatTestCase:
  def testDirectOwnerMatrixProducesExactCallsListsAndNamedAssignments(): Unit =
    val source =
      """def packageDef = call(name = value)
        |val packageVal = call(1, name = "text")
        |class Owner:
        |  def templateDef = target.call(first = value, second = 1)
        |  val templateVal = call(value, name = value, count = 1, text = "x")
        |""".stripMargin
    val file   = physical("NamedArguments1.scala", source)
    val calls  = descendants[ScMethodCall](file)

    assertEquals(
      Vector(
        "call(name = value)",
        "call(1, name = \"text\")",
        "target.call(first = value, second = 1)",
        "call(value, name = value, count = 1, text = \"x\")"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMethodCallImpl", call.getClass.getName)
      assertSame(call, call.getInvokedExpr.getParent)
      assertSame(call, call.args.getParent)
      assertEquals(call.args.exprs.toVector, call.argumentExpressions.toVector)
      assertFalse(call.args.isUsing)
      assertTrue(call.args.isArgsInParens)
      assertEquals(call.getText, call.getTextRange.substring(source))
    assertEquals(calls.size, descendants[ScArgumentExprList](file).size)

    val assignments = descendants[ScAssignment](file)
    assertEquals(
      Vector(
        "name = value",
        "name = \"text\"",
        "first = value",
        "second = 1",
        "name = value",
        "count = 1",
        "text = \"x\""
      ),
      assignments.map(_.getText)
    )
    assignments.foreach: assignment =>
      assertTrue(assignment.isInstanceOf[MetallurgyNamedArgument])
      assertSame(assignment, assignment.leftExpression.getParent)
      assertSame(assignment, assignment.rightExpression.get.getParent)
      assertTrue(assignment.leftExpression.isInstanceOf[ScReferenceExpression])
      assertTrue(assignment.isNamedParameter)
      assertEquals(Some(assignment.leftExpression.getText), assignment.referenceName)
      assertEquals("=", assignment.findFirstChildByType(ScalaTokenTypes.tASSIGN).get.getText)
      assertTrue(assignment.resolveAssignment.isEmpty)
      assertTrue(assignment.shapeResolveAssignment.isEmpty)
      assertTrue(assignment.mirrorMethodCall.isEmpty)
      assertSame(assignment.leftExpression, assignment.assignNavigationElement)
      assertTrue(assignment.getParent.isInstanceOf[ScArgumentExprList])
      var visited = false
      assignment.accept(
        new ScalaElementVisitor:
          override def visitAssignment(value: ScAssignment): Unit = visited = value eq assignment
      )
      assertTrue(visited)

  def testCommentsPreserveExactNameEqualsValueAndListPartitions(): Unit =
    val source =
      "val result = call(/* before name */ name /* before equals */ = /* before value */ value /* after value */, /* comma */ count/*e*/=/*v*/1)"
    val file   = physical("NamedArguments2.scala", source)
    val call   = descendants[ScMethodCall](file).head
    val named  = descendants[ScAssignment](file)

    assertEquals(source.stripPrefix("val result = "), call.getText)
    assertEquals(
      Vector(
        "name /* before equals */ = /* before value */ value",
        "count/*e*/=/*v*/1"
      ),
      named.map(_.getText)
    )
    named.foreach: assignment =>
      assertEquals(assignment.getText, assignment.getNode.getChildren(null).toVector.map(_.getText).mkString)
      assertEquals("=", assignment.asInstanceOf[MetallurgyNamedArgument].assignmentToken.get.getText)
      assertEquals(assignment.leftExpression.getText, assignment.referenceName.get)
      assertEquals(
        assignment.rightExpression.get.getText,
        assignment.rightExpression.get.getTextRange.substring(source)
      )
    assertEquals(source, file.getText)

  def testCopiesPointersNativeToUnsupportedEditsAndForcedReparseRemainAtomic(): Unit =
    val source   = "val result = call(name = value)\n"
    val file     = physical("NamedArguments3.scala", source)
    val call     = descendants[ScMethodCall](file).head
    val named    = descendants[ScAssignment](file).head
    val pointers = SmartPointerManager.getInstance(getProject)
    val callPtr  = pointers.createSmartPsiElementPointer(call)
    val namedPtr = pointers.createSmartPsiElementPointer(named)

    val copied = file.copy().asInstanceOf[com.intellij.psi.PsiFile]
    assertEquals("call(name = value)", descendants[ScMethodCall](copied).head.getText)
    assertEquals(Vector("name = value"), descendants[ScAssignment](copied).map(_.getText))

    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    replace(document, "call(name = value)", "call(name = inner(value))")
    assertNull(callPtr.getElement)
    assertNull(namedPtr.getElement)
    assertCompletePayload(file, "call(name = inner(value))")

    replace(document, "call(name = inner(value))", "call(name = value)")
    FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
    assertEquals(Vector("call(name = value)"), descendants[ScMethodCall](file).map(_.getText))
    assertEquals(Vector("name = value"), descendants[ScAssignment](file).map(_.getText))
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)

    FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
    assertEquals(Vector("call(name = value)"), descendants[ScMethodCall](file).map(_.getText))

  def testUnsupportedDirectNamedCallsRemainOneCompletePayloadAndExcludedCasesHaveNoNativeDescendants(): Unit =
    val validFallbacks = Vector(
      "val result = source.target.call(name = value)"   -> "source.target.call(name = value)",
      "val result = call(name = 1L)"                    -> "call(name = 1L)",
      "val result = call(name = true)"                  -> "call(name = true)",
      "val result = call(name = inner(value))"          -> "call(name = inner(value))",
      "val result = call(name = (value, 1))"            -> "call(name = (value, 1))",
      "val result = call(name = value, name = other)"   -> "call(name = value, name = other)",
      "val result = call(second = 2, value, third = 3)" -> "call(second = 2, value, third = 3)",
      "val result = call(using name = value)"           -> "call(using name = value)"
    )
    validFallbacks.zipWithIndex.foreach: (entry, index) =>
      val (source, expected) = entry
      val file               = physical(s"NamedArgumentsFallback${index + 1}.scala", source)
      assertCompletePayload(file, expected)

    val localSource = "def owner = { val local = call(name = value); local }"
    val localFile   = physical("NamedArgumentsFallbackLocal.scala", localSource)
    assertEquals(
      "{ val local = call(name = value); local }",
      descendants[MetallurgyExpressionPayload](localFile).head.getText
    )
    assertNoNativeNamedCallPsi(localFile)

    val malformed = "val result = call(name =)"
    val file      = loaded("NamedArgumentsMalformed.scala", malformed)
    assertTrue(descendants[PsiErrorElement](file).nonEmpty)
    assertNoNativeNamedCallPsi(file)

  def testNamedArgumentsRemainAstOnlyAcrossStubSerializationUnloadAndReload(): Unit =
    assertEquals(15, Scala3DotcFileElementType.SchemaVersion)
    assertEquals(
      "903f8bb2f85bab49b73220a82f058494655842996440315b10814ef5a5022b95",
      Scala3DotcFileElementType.PersistenceSchemaFingerprint
    )
    val source      = "package named\nval result = target.call(name = value, count = 1)\n"
    val file        = physical("NamedArgumentsPersistence.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val beforeShape = stubShape(stubs)
    val beforeIndex = indexShape(stubs)
    assertFalse(beforeShape.exists(row => row.contains("MethodCall") || row.contains("NamedArgument")))
    assertEquals(Vector("target.call(name = value, count = 1)"), descendants[ScMethodCall](file).map(_.getText))
    assertEquals(Vector("name = value", "count = 1"), descendants[ScAssignment](file).map(_.getText))

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
    assertEquals(Vector("target.call(name = value, count = 1)"), descendants[ScMethodCall](file).map(_.getText))
    assertEquals(Vector("name = value", "count = 1"), descendants[ScAssignment](file).map(_.getText))

  private def replace(document: com.intellij.openapi.editor.Document, before: String, after: String): Unit =
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf(before)
          assertTrue(start >= 0)
          document.replaceString(start, start + before.length, after)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)

  private def assertCompletePayload(file: com.intellij.psi.PsiFile, expected: String): Unit =
    val payloads = descendants[MetallurgyExpressionPayload](file)
    assertEquals(file.getText, Vector(expected), payloads.map(_.getText))
    assertEquals(file.getText, Vector(expected), descendants[ScExpression](file).map(_.getText))
    assertNoNativeNamedCallPsi(file)
    assertTrue(file.getText, descendants[ScExpression](payloads.head).isEmpty)

  private def assertNoNativeNamedCallPsi(file: com.intellij.psi.PsiFile): Unit =
    assertTrue(file.getText, descendants[ScMethodCall](file).isEmpty)
    assertTrue(file.getText, descendants[ScArgumentExprList](file).isEmpty)
    assertTrue(file.getText, descendants[ScAssignment](file).isEmpty)
    assertTrue(file.getText, descendants[MetallurgyNamedArgument](file).isEmpty)
    assertTrue(file.getText, descendants[ScReferenceExpression](file).isEmpty)
    assertTrue(file.getText, descendants[ScIntegerLiteral](file).isEmpty)
    assertTrue(file.getText, descendants[ScStringLiteral](file).isEmpty)

  private def descendants[A <: PsiElement: ClassTag](root: PsiElement): Vector[A] =
    PsiTreeUtil
      .findChildrenOfType(root, summon[ClassTag[A]].runtimeClass.asInstanceOf[Class[A]])
      .asScala
      .toVector

  private def stubShape(stubs: Iterable[Stub]): Vector[String] = stubs.iterator
    .flatMap(stub =>
      Option(stub.getStubSerializer).map(serializer => s"${stub.getClass.getName}|${serializer.getExternalId}")
    )
    .toVector

  private def indexShape(stubs: Iterable[Stub]): Vector[String] =
    val result = Vector.newBuilder[String]
    val sink   = new IndexSink:
      override def occurrence[Psi <: PsiElement, K](indexKey: StubIndexKey[K, Psi], value: K): Unit =
        result += s"${indexKey.toString}|${value.toString}"
    stubs.foreach(stub =>
      Option(stub.getStubSerializer).foreach(
        _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
      )
    )
    result.result()

  private def physical(name: String, source: String): com.intellij.psi.PsiFile =
    val file    = loaded(name, source)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    file

  private def loaded(name: String, source: String): com.intellij.psi.PsiFile =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    file.getChildren
    file

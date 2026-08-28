package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiComment, PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSequenceArg
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScArgumentExprList, ScMethodCall, ScTypedExpression}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.{ScReferenceExpressionImpl, ScTypedExpressionImpl}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag

final class Scala3RepeatedArgumentPsiTest extends Scala3CompatTestCase:
  def testDirectOwnerMatrixProducesNativeCallsTypedExpressionsAndSequenceArguments(): Unit =
    val source =
      """def packageDef = repeated(xs*)
        |val packageVal = texts(words*)
        |class Owner:
        |  def templateDef = source.repeated(values*)
        |  val templateVal = repeated(xs*)
        |def repeated(xs: Int*): Int = xs.sum
        |def texts(parts: String*): String = parts.mkString
        |val xs = Seq(1)
        |val words = Seq("a")
        |val values = Seq(2)
        |object source:
        |  def repeated(xs: Int*): Int = xs.sum
        |""".stripMargin
    val file   = physical("RepeatedArguments1.scala", source)
    val calls  = descendants[ScMethodCall](file)

    assertEquals(
      Vector(
        "repeated(xs*)",
        "texts(words*)",
        "source.repeated(values*)",
        "repeated(xs*)",
        "Seq(1)",
        "Seq(\"a\")",
        "Seq(2)"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMethodCallImpl", call.getClass.getName)
      assertSame(call, call.getInvokedExpr.getParent)
      assertSame(call, call.args.getParent)
      assertEquals(call.getText, call.getTextRange.substring(source))
      assertFalse(call.args.isUsing)
      assertTrue(call.args.isArgsInParens)
      assertEquals(call.args.exprs.toVector, call.argumentExpressions.toVector)
      assertEquals(1, call.args.getArgsCount)

    val typed = descendants[ScTypedExpression](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("xs*", "words*", "values*", "xs*"), typed.map(_.getText))
    typed.foreach: expression =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScTypedExpressionImpl", expression.getClass.getName)
      assertTrue(expression.isSequenceArg)
      assertTrue(expression.typeElement.isEmpty)
      assertFalse(expression.hasAnnotation)
      assertTrue(expression.annotations.isEmpty)
      assertTrue(expression.getParent.isInstanceOf[ScArgumentExprList])
      assertTrue(expression.expr.isInstanceOf[ScReferenceExpressionImpl])
      assertSame(expression, expression.expr.getParent)
      assertTrue(
        expression.expr.getText == "xs" || expression.expr.getText == "words" || expression.expr.getText ==
          "values"
      )
      var visited = false
      expression.accept(
        new ScalaElementVisitor:
          override def visitTypedExpr(value: ScTypedExpression): Unit = visited = value eq expression
      )
      assertTrue(visited)

    val sequences = descendants[ScSequenceArg](file)
    assertEquals(typed.size, sequences.size)
    sequences.foreach: sequence =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScSequenceArgImpl", sequence.getClass.getName)
      assertEquals("*", sequence.getText)
      assertEquals(1, sequence.getTextRange.getLength)
      val typedParent = sequence.getParent
      assertTrue(typedParent.isInstanceOf[ScTypedExpressionImpl])
      assertSame(sequence, typedParent.getLastChild)
      val star        = sequence.getNode.getChildren(null).toVector.map(_.getPsi)
      assertEquals(1, star.size)
      assertEquals("*", star.head.getText)
      assertSame(ScalaTokenTypes.tIDENTIFIER, star.head.getNode.getElementType)
      assertSame(sequence, star.head.getParent)

  def testTriviaBetweenOperandAndStarStaysOutsideTheSequenceArgument(): Unit =
    val source   = "val result = repeated(xs /* gap */ *)\ndef repeated(xs: Int*): Int = xs.sum\nval xs = Seq(1)\n"
    val file     = physical("RepeatedArguments2.scala", source)
    val typed    = descendants[ScTypedExpression](file).head
    assertEquals("xs /* gap */ *", typed.getText)
    val sequence = descendants[ScSequenceArg](file).head
    assertEquals("*", sequence.getText)
    assertEquals(1, sequence.getTextRange.getLength)
    assertEquals(typed.getTextRange.getEndOffset, sequence.getTextRange.getEndOffset)
    assertSame(sequence, typed.getLastChild)
    val comments = PsiTreeUtil.findChildrenOfType(file, classOf[PsiComment]).asScala.toVector
    assertEquals(Vector("/* gap */"), comments.map(_.getText))
    comments.foreach: comment =>
      assertFalse(sequence.getTextRange.intersects(comment.getTextRange))
      assertTrue(typed.getTextRange.contains(comment.getTextRange))

  def testTypedArgumentsStayNativeAcrossEditsReparseAndSerialization(): Unit =
    val initial  = "val result = repeated(xs*)\ndef repeated(xs: Int*): Int = xs.sum\nval xs = Seq(1)\n"
    val file     = physical("RepeatedArguments3.scala", initial)
    val document = PsiDocumentManager
      .getInstance(getProject)
      .getDocument(file)

    def replace(before: String, after: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            val start = document.getText.indexOf(before)
            assertTrue(start >= 0)
            document.replaceString(start, start + before.length, after)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)

    replace("xs*", "xs /* keep */ *")
    assertEquals(initial.replace("xs*", "xs /* keep */ *"), file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val typedAfter = descendants[ScTypedExpression](file).head
    assertEquals("xs /* keep */ *", typedAfter.getText)
    assertTrue(typedAfter.isInstanceOf[ScTypedExpressionImpl])
    assertTrue(typedAfter.isSequenceArg)
    assertEquals("*", descendants[ScSequenceArg](file).head.getText)

    val stubTree      = file.asInstanceOf[PsiFileImpl].calcStubTree
    val output        = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(stubTree.getRoot, output)
    val bytes         = output.toByteArray
    val restored      = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    val repeatedBytes = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeatedBytes)
    assertArrayEquals(bytes, repeatedBytes.toByteArray)

    replace("xs /* keep */ *", "xs*")
    assertEquals(initial, file.getText)
    val reparsed = descendants[ScTypedExpression](file).head
    assertEquals("xs*", reparsed.getText)
    assertTrue(reparsed.isInstanceOf[ScTypedExpressionImpl])
    assertTrue(reparsed.isSequenceArg)
    assertFalse(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.nonEmpty)

  def testUnsupportedStarShapesRemainOneCompletePayload(): Unit =
    val source =
      """def local =
        |  val localValue = repeated(xs*)
        |  localValue
        |def repeated(xs: Int*): Int = xs.sum
        |def single(value: Int): Int = value
        |val selectedOperand = repeated(source.values*)
        |val ascribedOnly = single(1: Int)
        |val xs = Seq(1)
        |val values = Seq(2)
        |object source:
        |  def values: Seq[Int] = Seq(9)
        |""".stripMargin
    val file   = physical("RepeatedArguments4.scala", source)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

    val payloads = descendants[MetallurgyExpressionPayload](file).sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector(
        "val localValue = repeated(xs*)\n  localValue",
        "repeated(xs*)",
        "repeated",
        "xs",
        "repeated(source.values*)",
        "single(1: Int)"
      ),
      payloads.map(_.getText)
    )
    assertTrue(descendants[ScMethodCall](file).forall(!_.getText.contains("repeated")))
    assertTrue(descendants[ScTypedExpression](file).isEmpty)
    assertTrue(descendants[ScSequenceArg](file).isEmpty)
    payloads.foreach: payload =>
      assertEquals(payload.getText, payload.getTextRange.substring(source))

  def testNamedAndRepeatedMixesFailClosedUntilWired(): Unit =
    val source  =
      """def named(first: Int, xs: Int*): Int = first + xs.sum
        |val namedLeading = named(first = 1, xs*)
        |val ascribed = named(1: Int, xs*)
        |val xs = Seq(1)
        |""".stripMargin
    val pending = myFixture.addFileToProject("src/RepeatedArguments5.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    file.getChildren
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isDefined)
    assertTrue(descendants[ScMethodCall](file).isEmpty)
    assertTrue(descendants[ScTypedExpression](file).isEmpty)
    assertTrue(descendants[ScSequenceArg](file).isEmpty)
    assertTrue(descendants[MetallurgyExpressionPayload](file).isEmpty)

  private def descendants[T <: PsiElement: ClassTag](file: com.intellij.psi.PsiFile): Vector[T] =
    PsiTreeUtil
      .findChildrenOfType(file, implicitly[ClassTag[T]].runtimeClass.asInstanceOf[Class[T]])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)

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

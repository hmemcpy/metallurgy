package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.FileContentUtilCore
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertEquals, assertFalse, assertNull, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3PositionalTypeApplicationPsiTest extends Scala3CompatTestCase:
  def testDirectPositionalTypeApplicationsExposeExactNativeShape(): Unit =
    val source =
      """def direct = f[Int]
        |val applied = f[Int](x)
        |val selected = source.f[String]
        |val selectedApplied = source.f[String](x)
        |val many = f[Int, String]
        |val nested = f[List[String], Map[Int, List[String]]]
        |val comments = f[/* before */ Int, List[/* nested */ String] /* after */]
        |class Owner:
        |  val member = source.f[String]
        |""".stripMargin
    val file   = physical("PositionalTypeApplications1.scala", source)
    val calls  = genericCalls(file)

    assertEquals(
      Vector(
        "f[Int]",
        "f[Int]",
        "source.f[String]",
        "source.f[String]",
        "f[Int, String]",
        "f[List[String], Map[Int, List[String]]]",
        "f[/* before */ Int, List[/* nested */ String] /* after */]",
        "source.f[String]"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScGenericCallImpl", call.getClass.getName)
      assertEquals(call.getText, call.getTextRange.substring(source))
      assertSame(call, call.referencedExpr.getParent)
      assertSame(call, call.typeArgs.getParent)
      assertEquals(
        Vector(call.referencedExpr, call.typeArgs),
        call.getChildren.toVector.collect { case child: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement =>
          child
        }
      )
      assertEquals(
        "org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScTypeArgsImpl",
        call.typeArgs.getClass.getName
      )
      assertEquals(
        "org.jetbrains.plugins.scala.lang.psi.impl.expr.ScReferenceExpressionImpl",
        call.referencedExpr.getClass.getName
      )
      assertEquals(call.referencedExpr.getText, call.referencedExpr.getTextRange.substring(source))
    val references  = calls.map(_.referencedExpr.asInstanceOf[ScReferenceExpression])
    assertEquals(Vector(None, None, Some("source"), Some("source")), references.take(4).map(_.qualifier.map(_.getText)))
    references.take(2).foreach(reference => assertEquals(reference.refName, reference.getText))
    references
      .slice(2, 4)
      .foreach: reference =>
        assertEquals("f", reference.refName)
        assertEquals("f", reference.nameId.getText)
        assertSame(reference, reference.nameId.getParent)
        val qualifier = reference.qualifier.get.asInstanceOf[ScReferenceExpression]
        assertEquals("source", qualifier.getText)
        assertEquals(
          "org.jetbrains.plugins.scala.lang.psi.impl.expr.ScReferenceExpressionImpl",
          qualifier.getClass.getName
        )
        assertSame(reference, qualifier.getParent)
    val methodCalls = PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.toVector
    assertEquals(Vector("f[Int](x)", "source.f[String](x)"), methodCalls.map(_.getText))
    methodCalls.foreach(call => assertTrue(call.getInvokedExpr.isInstanceOf[ScGenericCall]))
    assertSame(calls(1), methodCalls.head.getInvokedExpr)
    assertSame(calls(3), methodCalls(1).getInvokedExpr)

    var visited: ScGenericCall = null
    calls.head.accept(
      new ScalaElementVisitor:
        override def visitGenericCallExpression(call: ScGenericCall): Unit = visited = call
    )
    assertSame(calls.head, visited)

  def testCopyPointerEditAndFallbackReparseRemainDeterministic(): Unit =
    val source   = "import scala.language.experimental.namedTypeArguments\nval result = source.f[Int](x)\n"
    val file     = physical("PositionalTypeApplications2.scala", source)
    val generic  = genericCalls(file).head
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(generic)
    val copied   = genericCalls(file.copy().asInstanceOf[com.intellij.psi.PsiFile]).head
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)

    assertEquals(generic.getText, copied.getText)
    assertEquals(generic.typeArgs.getText, copied.typeArgs.getText)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(source.indexOf("Int"), source.indexOf("Int") + 3, "String")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("source.f[String]", pointer.getElement.getText)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(document.getText.indexOf("String"), "A = ")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("source.f[A = String]", pointer.getElement.getText)
    FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
    val reparsed = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
    assertEquals(Vector("source.f[A = String]"), genericCalls(reparsed).map(_.getText))
    assertNull(PsiTreeUtil.findChildOfType(reparsed, classOf[MetallurgyExpressionPayload]))

  def testExcludedFormsNeverExposeGenericCalls(): Unit =
    val sources = Vector(
      "val result = f[Int](1)",
      "val result = f[Int](\"text\")",
      "val result = f[Int]()",
      "val result = f[Int](using x)",
      "val result = f[Int](name = x)",
      "val result = f[Int](xs*)",
      "val result = f[Int](if condition then x else y)",
      "val result = new C[Int](x)",
      "def owner = { val local = f[Int]; local }",
      "def owner(value: f[Int] = f[Int]) = value",
      "class C extends Parent[f[Int]]",
      "type R = AnyRef { val value: f[Int] }"
    )
    sources.zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/PositionalTypeApplicationExcluded${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      file.getChildren
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).isEmpty)

  def testGenericCallsRemainAstOnlyAndPersistenceSchemaIsUnchanged(): Unit =
    assertEquals(15, Scala3DotcFileElementType.SchemaVersion)
    assertEquals(
      "5cd7483d0d73563e0f32069a04ac406e512b3776ee08553ba4bf4b979b1fde32",
      Scala3DotcFileElementType.PersistenceSchemaFingerprint
    )
    val source      = "package positional\nval result = source.f[List[String]]\n"
    val file        = physical("PositionalTypeApplications3.scala", source).asInstanceOf[PsiFileImpl]
    val beforeStubs = file.calcStubTree.getPlainList.asScala.map(_.getClass.getName).toVector
    assertFalse(beforeStubs.exists(name => name.contains("GenericCall") || name.contains("TypeArgs")))
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(beforeStubs, file.getStubTree.getPlainList.asScala.map(_.getClass.getName).toVector)
    assertEquals(Vector("source.f[List[String]]"), genericCalls(file).map(_.getText))

  private def genericCalls(file: com.intellij.psi.PsiFile): Vector[ScGenericCall] =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScGenericCall])
      .asScala
      .toVector
      .sortBy(call => (call.getTextRange.getStartOffset, -call.getTextLength))

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

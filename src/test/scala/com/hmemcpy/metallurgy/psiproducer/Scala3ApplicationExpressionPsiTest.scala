package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.{PsiErrorElement, PsiManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScArgumentExprList, ScMethodCall}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeArgs
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{MetallurgyExpressionPayload, MetallurgyTypeArguments}
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3ApplicationExpressionPsiTest extends Scala3CompatTestCase:
  def testOrdinaryDirectRhsApplicationsExposeNativeCallsAndArgumentLists(): Unit =
    val source =
      """def empty = f()
        |val one = f(x)
        |var many = f(x, 1)
        |val selected = source.member(x)
        |def nested = outer(inner(x), source.member(1))
        |val curried = f()(x)
        |class Parent:
        |  def inherited = 1
        |class C extends Parent:
        |  val member = f(this)
        |  val thisApplied = this.member()
        |  val superApplied = super.inherited()
        |""".stripMargin
    val file   = physical("ApplicationExpressions1.scala", source)
    val calls  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScMethodCall])
      .asScala
      .toVector
      .sortBy(call => (call.getTextRange.getStartOffset, -call.getTextLength))

    assertEquals(
      Vector(
        "f()",
        "f(x)",
        "f(x, 1)",
        "source.member(x)",
        "outer(inner(x), source.member(1))",
        "inner(x)",
        "source.member(1)",
        "f()(x)",
        "f()",
        "f(this)",
        "this.member()",
        "super.inherited()"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMethodCallImpl", call.getClass.getName)
      assertSame(call, call.getInvokedExpr.getParent)
      assertSame(call, call.args.getParent)
      assertEquals(call.args.exprs.toVector, call.argumentExpressions.toVector)
      assertTrue(call.args.isArgsInParens)
      assertFalse(call.args.isUsing)
      assertEquals(call.args.exprs.size, call.args.getArgsCount)
      assertEquals(call.getText, call.getTextRange.substring(source))
    assertEquals(calls.size, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).size)
    val stubClasses = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.map(_.getClass.getName)
    assertFalse(stubClasses.exists(_.contains("MethodCall")))

  def testUnsupportedApplicationsRemainOneOpaquePayloadWithoutNativeDescendants(): Unit =
    val sources = Vector(
      "val value = f(name = x)"                 -> Some("f(name = x)"),
      "val value = f((x, y))"                   -> Some("f((x, y))"),
      "val value = f { x }"                     -> Some("f { x }"),
      "val value = xs map f"                    -> Some("xs map f"),
      "def owner = { val local = f(x); local }" -> None
    )
    sources.zipWithIndex.foreach: (entry, index) =>
      val (source, expectedPayload) = entry
      val file                      = physical(s"ApplicationFallback$index.scala", source)
      val payloads                  = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
      expectedPayload.foreach(expected => assertEquals(source, Vector(expected), payloads.map(_.getText)))
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)

  def testExcludedTypeApplicationsKeepOnePayloadAndTheirExistingTypeArgumentPsi(): Unit =
    val source        =
      """import scala.language.experimental.namedTypeArguments
        |val positional = f[Int](x)
        |val selected = source.f[String](x)
        |val named = f[A = Int](x)
        |""".stripMargin
    val file          = physical("ApplicationTypeFallback.scala", source)
    val expected      = Vector("f[Int](x)", "source.f[String](x)", "f[A = Int](x)")
    val payloads      = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .filter(value => expected.contains(value.getText))
      .sortBy(_.getTextRange.getStartOffset)
    val typeArguments =
      payloads.flatMap(payload => PsiTreeUtil.findChildrenOfType(payload, classOf[ScTypeArgs]).asScala)

    assertEquals(expected, payloads.map(_.getText))
    assertEquals(Vector("[Int]", "[String]", "[A = Int]"), typeArguments.map(_.getText))
    assertEquals(
      Vector("A = Int"),
      typeArguments
        .collect { case value: MetallurgyTypeArguments => value }
        .flatMap(_.namedTypeArguments)
        .map(_.getText)
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)

  def testConstructorSyntheticApplicationRemainsASeparateHardNegative(): Unit =
    val source  = "val value = new C(x)"
    val pending = myFixture.addFileToProject("src/ApplicationConstructor.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    file.getChildren
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))

    assertTrue(failure.toString, failure.exists(_.detail.contains("UncoveredCompilerShape")))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)

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

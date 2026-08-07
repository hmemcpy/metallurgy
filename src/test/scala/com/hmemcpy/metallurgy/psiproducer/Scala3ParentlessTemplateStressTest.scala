package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.{PsiErrorElement, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParenthesisedTypeElement, ScSimpleTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScTypeParam, ScTypeParamClause}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertEquals, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3ParentlessTemplateStressTest extends Scala3CompatTestCase:

  def testDeepNestingHasNoFiniteDepthCap(): Unit =
    val depth        = 256
    val nestedSource = (0 until depth)
      .map: index =>
        val suffix = if index == depth - 1 then "\n" else ":\n"
        s"  " * index + s"class C$index$suffix"
      .mkString
    val nested       = physical("Case7.scala", nestedSource)
    assertEquals(depth, PsiTreeUtil.findChildrenOfType(nested, classOf[ScClass]).size)

  def testTenThousandOwnersHaveNoFiniteCountCap(): Unit =
    val count       = 10000
    val ownerSource = (0 until count).map(index => s"class C$index\n").mkString
    val owners      = physical("Case8.scala", ownerSource)
    assertEquals(count, PsiTreeUtil.findChildrenOfType(owners, classOf[ScClass]).size)

  def testTenThousandUnboundedTypeParametersAndRepeatedEmptyClausesHaveNoFiniteCountCap(): Unit =
    val count  = 10000
    val source = (0 until count).map(index => s"class Generic$index[T$index]()()\n").mkString
    val file   = physical("Case9.scala", source)

    val classes = PsiTreeUtil.findChildrenOfType(file, classOf[ScClass])
    assertEquals(count, classes.size)
    assertEquals(count, classes.stream().mapToInt(owner => owner.typeParameters.size).sum())
    assertTrue(
      classes
        .stream()
        .allMatch(owner => owner.constructor.get.parameterList.clauses.map(_.getText).toVector == Vector("()", "()"))
    )

  def testDeepAlreadyAdmittedOwnersRetainUnboundedTypeParametersAndRepeatedEmptyClauses(): Unit =
    val depth  = 256
    val source = (0 until depth)
      .map: index =>
        val suffix = if index == depth - 1 then "\n" else ":\n"
        s"  " * index + s"class Generic$index[T$index]()()$suffix"
      .mkString
    val file   = physical("Case10.scala", source)

    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).size)
    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParamClause]).size)
    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).size)

  def testDeepExactExpressionPayloadsHaveNoFiniteDepthCap(): Unit =
    val depth  = 1024
    val rhs    = "root" + ".next" * depth
    val source = s"def deep = $rhs\n"
    val file   = physical("Case11.scala", source)

    val payloads = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
    assertEquals(depth + 1, payloads.size)
    assertEquals(rhs, payloads.maxBy(_.getTextLength).getText)
    assertTrue(
      payloads.forall: payload =>
        val range = payload.getTextRange
        source.substring(range.getStartOffset, range.getEndOffset) == payload.getText
    )

  def testDeepParenthesizedSingletonTypeHasNoMetallurgyDepthCap(): Unit =
    val depth  = 384
    val atom   = "(" * depth + "x.type" + ")" * depth
    val source = s"import a.b.given $atom\n"
    val file   = physical("Case13.scala", source)

    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScParenthesisedTypeElement]).size)
    assertEquals(
      1,
      PsiTreeUtil
        .findChildrenOfType(file, classOf[ScSimpleTypeElement])
        .asScala
        .count(_.getText == "x.type")
    )

  def testTenThousandRepeatedRhsPayloadsHaveNoFiniteOccurrenceCap(): Unit =
    val count  = 10000
    val source = (0 until count).map(index => s"val value$index = $index\n").mkString
    val file   = physical("Case12.scala", source)

    val payloads = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
    assertEquals(count, payloads.size)
    assertEquals(count, PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternDefinition]).size)
    assertTrue(
      payloads.forall: payload =>
        val range = payload.getTextRange
        source.substring(range.getStartOffset, range.getEndOffset) == payload.getText
    )

  private def physical(name: String, source: String): com.intellij.psi.PsiFile =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val errors  = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement])
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(source, file.getText)
    assertTrue(errors.isEmpty)
    file

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.{PsiErrorElement, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScParameterizedTypeElement,
  ScParenthesisedTypeElement,
  ScSimpleTypeElement,
  ScTypeArgs,
  ScTypeLambdaTypeElement,
  ScWildcardTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScIntegerLiteral
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScPatternDefinition, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScTypeParam, ScTypeParamClause}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyTypeArguments
import org.junit.Assert.{assertEquals, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3RepresentativeScalePsiTest extends Scala3CompatTestCase:

  def testMixedGenericDefinitionsAndAtomicIntegerRhsRetainExactSourceOrder(): Unit =
    val count  = 32
    val source = (0 until count).map(index => s"class Generic$index[T$index]()()\nval value$index = $index\n").mkString
    val file   = physical("Case9.scala", source)

    val classes  = ordered(file, classOf[ScClass])
    val patterns = ordered(file, classOf[ScPatternDefinition])
    val integers = ordered(file, classOf[ScIntegerLiteral])
    assertEquals(count, classes.size)
    assertEquals(count, patterns.size)
    assertEquals(count, integers.size)
    assertTrue(ordered(file, classOf[MetallurgyExpressionPayload]).isEmpty)
    representativeIndices(count).foreach: index =>
      val owner = classes(index)
      assertEquals(s"class Generic$index[T$index]()()", owner.getText)
      assertEquals(owner.getText, owner.getTextRange.substring(source))
      assertEquals(Vector(s"T$index"), owner.typeParameters.map(_.name).toVector)
      assertEquals(Vector("()", "()"), owner.constructor.get.parameterList.clauses.map(_.getText).toVector)
      owner.constructor.get.parameterList.clauses.foreach(clause => assertSame(owner.constructor.get, clause.owner))
      assertEquals(s"val value$index = $index", patterns(index).getText)
      assertEquals(index.toString, integers(index).getText)
      assertEquals(index, integers(index).getValue)
      assertEquals(integers(index).getText, integers(index).getTextRange.substring(source))
      assertSame(patterns(index), integers(index).getParent)

  def testNestedGenericOwnersRetainEveryAdjacentParentAndExactAccessors(): Unit =
    val depth  = 16
    val source = (0 until depth)
      .map: index =>
        val suffix = if index == depth - 1 then "\n" else ":\n"
        s"  " * index + s"class Generic$index[T$index]()()$suffix"
      .mkString
    val file   = physical("Case10.scala", source)

    val classes = ordered(file, classOf[ScClass])
    assertEquals(depth, classes.size)
    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParamClause]).size)
    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).size)
    representativeIndices(depth).foreach: index =>
      assertEquals(s"Generic$index", classes(index).name)
      assertEquals(Vector(s"T$index"), classes(index).typeParameters.map(_.name).toVector)
      assertEquals(Vector("()", "()"), classes(index).constructor.get.parameterList.clauses.map(_.getText).toVector)
      assertEquals(classes(index).getText, classes(index).getTextRange.substring(source))
    classes.indices
      .drop(1)
      .foreach(index =>
        assertSame(classes(index - 1), PsiTreeUtil.getParentOfType(classes(index), classOf[ScClass], true))
      )

  def testNestedExpressionPayloadsRetainEveryAdjacentParentAndExactRanges(): Unit =
    val depth  = 16
    val rhs    = "root" + ".next" * depth
    val source = s"def deep = $rhs\n"
    val file   = physical("Case11.scala", source)

    val payloads = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .sortBy(_.getTextLength)
    assertEquals(depth + 1, payloads.size)
    representativeIndices(depth + 1).foreach: index =>
      val payload = payloads(index)
      assertEquals("root" + ".next" * index, payload.getText)
      assertEquals(payload.getText, payload.getTextRange.substring(source))
    payloads.indices.drop(1).foreach(index => assertSame(payloads(index), payloads(index - 1).getParent))

  def testNestedParenthesizedSingletonAndAppliedTypesRetainExactPhysicalWrappers(): Unit =
    val depth  = 16
    val atom   = "(" * depth + "x.type" + ")" * depth
    val nested = (0 until depth).foldLeft("Leaf")((value, index) => s"F$index[$value]")
    val source = s"import a.b.given $atom\ntype Deep = $nested\n"
    val file   = physical("Case13.scala", source)

    val parentheses = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParenthesisedTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextLength)
    assertEquals(depth, parentheses.size)
    representativeIndices(depth).foreach: index =>
      assertEquals("(" * (index + 1) + "x.type" + ")" * (index + 1), parentheses(index).getText)
      assertEquals(parentheses(index).getText, parentheses(index).getTextRange.substring(source))
    parentheses.indices.drop(1).foreach(index => assertSame(parentheses(index), parentheses(index - 1).getParent))
    assertEquals(
      1,
      PsiTreeUtil
        .findChildrenOfType(file, classOf[ScSimpleTypeElement])
        .asScala
        .count(_.getText == "x.type")
    )
    val applied     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameterizedTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextLength)
    assertEquals(depth, applied.size)
    assertEquals(depth, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeArgs]).size)
    representativeIndices(depth).foreach: index =>
      val argument = (0 until index).foldLeft("Leaf")((value, current) => s"F$current[$value]")
      val expected = s"F$index[$argument]"
      assertEquals(expected, applied(index).getText)
      assertEquals(s"F$index", applied(index).typeElement.getText)
      assertEquals(s"[$argument]", applied(index).typeArgList.getText)
      assertSame(applied(index), applied(index).typeElement.getParent)
      assertSame(applied(index), applied(index).typeArgList.getParent)
    applied.indices.drop(1).foreach(index => assertSame(applied(index), applied(index - 1).getParent.getParent))

  def testRepeatedTypeArgumentsBoundsAndWildcardsRetainExactOrderAndAccessors(): Unit =
    val count      = 32
    val positional = (0 until count).map(index => s"T$index").mkString(", ")
    val named      = (0 until count).map(index => s"A$index = T$index").mkString(", ")
    val parameters = (0 until count).map(index => s"B$index >: Low <: High").mkString(", ")
    val wildcards  = (0 until count).map(_ => "? >: Low <: High").mkString(", ")
    val source     =
      s"import scala.language.experimental.namedTypeArguments\ntrait High\ntrait Low extends High\ntype Positional = F[$positional]\nval namedValue = make[$named]\ntrait Bounded[$parameters]\ntype Wild = F[$wildcards]\n"
    val file       = physical("Case15.scala", source)

    val positionalList = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeArgs])
      .asScala
      .find(!_.isInstanceOf[MetallurgyTypeArguments])
      .get
    val namedList      = PsiTreeUtil.findChildOfType(file, classOf[MetallurgyTypeArguments])
    assertEquals(count, positionalList.typeArgs.size)
    assertEquals(count, namedList.logicalTypeArguments.size)
    assertEquals(count, namedList.namedTypeArguments.size)
    assertTrue(namedList.typeArgs.isEmpty)
    representativeIndices(count).foreach: index =>
      assertEquals(s"T$index", positionalList.typeArgs(index).getText)
      assertEquals(s"A$index = T$index", namedList.logicalTypeArguments(index).getText)
      assertEquals(Some(s"A$index"), namedList.namedTypeArguments(index).name)
      assertEquals(Some(s"T$index"), namedList.namedTypeArguments(index).typeElement.map(_.getText))
      assertSame(namedList, namedList.namedTypeArguments(index).getParent)

    val typeParameters = ordered(file, classOf[ScTypeParam])
    val wildcardTypes  = ordered(file, classOf[ScWildcardTypeElement])
    assertEquals(count, typeParameters.size)
    assertEquals(count, wildcardTypes.size)
    representativeIndices(count).foreach: index =>
      val parameter = typeParameters(index)
      assertEquals(s"B$index >: Low <: High", parameter.getText)
      assertEquals(parameter.getText, parameter.getTextRange.substring(source))
      assertEquals(Some("Low"), parameter.lowerTypeElement.map(_.getText))
      assertEquals(Some("High"), parameter.upperTypeElement.map(_.getText))
      assertSame(parameter, parameter.lowerTypeElement.get.getParent)
      assertSame(parameter, parameter.upperTypeElement.get.getParent)
      val wildcard  = wildcardTypes(index)
      assertEquals("? >: Low <: High", wildcard.getText)
      assertEquals(Some("Low"), wildcard.lowerTypeElement.map(_.getText))
      assertEquals(Some("High"), wildcard.upperTypeElement.map(_.getText))
      assertSame(wildcard, wildcard.lowerTypeElement.get.getParent)
      assertSame(wildcard, wildcard.upperTypeElement.get.getParent)

  def testNestedBoundedLambdasAndAppliedTypesRetainEveryAdjacentParentAndAccessor(): Unit =
    val depth       = 16
    val lambdaTexts = nestedBoundedLambdaTexts(depth)
    val source      =
      s"trait High\ntrait Low extends High\ntype Wrap[F[X >: Low <: High]] = F[Low]\ntype Deep = ${lambdaTexts.head}\n"
    val file        = physical("Case17.scala", source)
    val deep        = ordered(file, classOf[ScTypeAliasDefinition]).find(_.name == "Deep").get

    val lambdas    = ordered(deep, classOf[ScTypeLambdaTypeElement])
    val parameters = ordered(deep, classOf[ScTypeParam])
    val applied    = ordered(deep, classOf[ScParameterizedTypeElement])
    assertEquals(depth, lambdas.size)
    assertEquals(depth, parameters.size)
    assertEquals(depth, applied.size)
    representativeIndices(depth).foreach: index =>
      assertEquals(lambdaTexts(index), lambdas(index).getText)
      assertEquals(lambdas(index).getText, lambdas(index).getTextRange.substring(source))
      assertEquals(Vector(s"X$index"), lambdas(index).typeParameters.map(_.name).toVector)
      val expectedResult = if index == depth - 1 then s"List[X$index]" else s"Wrap[${lambdaTexts(index + 1)}]"
      assertEquals(Some(expectedResult), lambdas(index).resultTypeElement.map(_.getText))
      assertEquals(expectedResult, applied(index).getText)
      assertEquals(Some("Low"), parameters(index).lowerTypeElement.map(_.getText))
      assertEquals(Some("High"), parameters(index).upperTypeElement.map(_.getText))
      assertSame(lambdas(index), lambdas(index).typeParametersClause.get.getParent)
      assertSame(lambdas(index), applied(index).getParent)
    lambdas.indices
      .drop(1)
      .foreach(index =>
        assertSame(
          applied(index - 1),
          PsiTreeUtil.getParentOfType(lambdas(index), classOf[ScParameterizedTypeElement], true)
        )
      )

  private def nestedBoundedLambdaTexts(depth: Int): Vector[String] =
    val result = Array.ofDim[String](depth)
    var index  = depth - 1
    var body   = s"List[X$index]"
    while index >= 0 do
      result(index) = s"[X$index >: Low <: High] =>> $body"
      if index > 0 then body = s"Wrap[${result(index)}]"
      index -= 1
    result.toVector

  private def representativeIndices(size: Int): Vector[Int] = Vector(0, size / 2, size - 1).distinct

  private def ordered[A <: com.intellij.psi.PsiElement](
      root: com.intellij.psi.PsiElement,
      elementClass: Class[A]
  ): Vector[A] =
    PsiTreeUtil.findChildrenOfType(root, elementClass).asScala.toVector.sortBy(_.getTextRange.getStartOffset)

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

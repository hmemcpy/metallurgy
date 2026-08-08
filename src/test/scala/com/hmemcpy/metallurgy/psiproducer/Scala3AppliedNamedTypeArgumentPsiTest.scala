package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParameterizedTypeElement, ScSimpleTypeElement, ScTypeArgs}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScArgumentExprList,
  ScExpression,
  ScGenericCall,
  ScMethodCall,
  ScReferenceExpression
}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{MetallurgyExpressionPayload, MetallurgyTypeArguments}
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3AppliedNamedTypeArgumentPsiTest extends Scala3CompatTestCase:
  def testAppliedTypesAndExpressionTypeApplicationIslandsUseExactPhysicalPsi(): Unit =
    val source =
      """import scala.language.experimental.namedTypeArguments
        |
        |type One = List[Int]
        |type Two = Either[Int, String]
        |type Three = Coll[Elem]
        |
        |val direct = /*start*/make[A = Int]/*end*/
        |//Int
        |val commented = make[A /*left*/ = /*right*/ Int]
        |val invoked = /*start*/pair[A = Int](1, "text")/*end*/
        |//String
        |val positional = pair[Int, String](1, "text")
        |val allNamed = pair[A = Int, B = String](1, "text")
        |""".stripMargin
    val file   = physical("AppliedNamedTypeArguments1.scala", source)

    val parameterized = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameterizedTypeElement])
      .asScala
      .toVector
      .filter(value => Set("List[Int]", "Either[Int, String]", "Coll[Elem]")(value.getText))
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("List[Int]", "Either[Int, String]", "Coll[Elem]"), parameterized.map(_.getText))
    assertEquals(Vector("List", "Either", "Coll"), parameterized.map(_.typeElement.getText))
    assertEquals(Vector("[Int]", "[Int, String]", "[Elem]"), parameterized.map(_.typeArgList.getText))
    assertEquals(
      Vector(Vector("Int"), Vector("Int", "String"), Vector("Elem")),
      parameterized.map(_.typeArgList.typeArgs.map(_.getText).toVector)
    )
    parameterized.foreach: value =>
      assertSame(value, value.typeElement.getParent)
      assertSame(value, value.typeArgList.getParent)
      assertEquals(
        Vector(value.typeElement, value.typeArgList),
        value.getChildren.toVector.filterNot(_.getText.isBlank)
      )
      assertTrue(value.typeArgList.getClass.getName.endsWith("ScTypeArgsImpl"))

    val expectedExpressions = Vector(
      "make[A = Int]",
      "make[A /*left*/ = /*right*/ Int]",
      "pair[A = Int](1, \"text\")",
      "pair[Int, String](1, \"text\")",
      "pair[A = Int, B = String](1, \"text\")"
    )
    val expressions         = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .filter(value => expectedExpressions.contains(value.getText))
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(expectedExpressions, expressions.map(_.getText))
    assertTrue(expressions.forall(_.getClass == classOf[MetallurgyExpressionPayload]))
    assertTrue(expressions.forall(value => value.getTextRange.substring(source) == value.getText))
    Vector("make[A = Int]", "pair[A = Int](1, \"text\")").foreach: text =>
      val start = source.indexOf(text)
      assertSame(
        expressions.find(_.getText == text).get,
        PsiTreeUtil.findElementOfClassAtRange(file, start, start + text.length, classOf[ScExpression])
      )

    val typeArgs   = expressions.flatMap(value => PsiTreeUtil.findChildrenOfType(value, classOf[ScTypeArgs]).asScala)
    assertEquals(
      Vector("[A = Int]", "[A /*left*/ = /*right*/ Int]", "[A = Int]", "[Int, String]", "[A = Int, B = String]"),
      typeArgs.map(_.getText)
    )
    val namedLists = typeArgs.collect { case value: MetallurgyTypeArguments => value }
    assertEquals(4, namedLists.size)
    assertEquals(Vector(1, 1, 1, 2), namedLists.map(_.logicalTypeArguments.size))
    assertEquals(Vector(1, 1, 1, 2), namedLists.map(_.namedTypeArguments.size))
    assertEquals(Vector(0, 0, 0, 0), namedLists.map(_.typeArgs.size))

    val named = namedLists.flatMap(_.namedTypeArguments)
    assertEquals(Vector("A", "A", "A", "A", "B"), named.flatMap(_.name))
    assertEquals(Vector("Int", "Int", "Int", "Int", "String"), named.flatMap(_.typeElement).map(_.getText))
    assertEquals(
      Vector("A = Int", "A /*left*/ = /*right*/ Int", "A = Int", "A = Int", "B = String"),
      named.map(_.getText)
    )
    named.foreach: argument =>
      assertTrue(argument.isNamed)
      assertTrue(argument.`type`().isRight)
      assertSame(argument, argument.nameElement.get.getParent)
      assertSame(argument, argument.typeElement.get.getParent)
      assertTrue(argument.nameElement.get.getClass.getName.endsWith("ScStableCodeReferenceImpl"))
      assertTrue(argument.typeElement.get.isInstanceOf[ScSimpleTypeElement])
      assertEquals(
        Vector(argument.nameElement.get, argument.typeElement.get),
        argument.getChildren.toVector.collect { case value: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement =>
          value
        }
      )

    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)
    assertFalse(PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).isEmpty)

  def testCopiesPointersAndPositionalNamedReparsePreserveExactIslands(): Unit =
    val source  =
      """import scala.language.experimental.namedTypeArguments
        |val result = make[A = Int]
        |""".stripMargin
    val file    = physical("AppliedNamedTypeArguments2.scala", source)
    val payload = PsiTreeUtil.findChildOfType(file, classOf[MetallurgyExpressionPayload])
    val pointer = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(payload)
    val copy    = file.copy()

    assertEquals("[A = Int]", PsiTreeUtil.findChildOfType(copy, classOf[MetallurgyTypeArguments]).getText)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("A = Int"), source.indexOf("A = Int") + 7, "Int")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("make[Int]", pointer.getElement.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyTypeArguments]).isEmpty)
    assertEquals("[Int]", PsiTreeUtil.findChildOfType(file, classOf[ScTypeArgs]).getText)

    val positional = file.getText
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = positional.indexOf("make[Int]") + "make[".length
          document.replaceString(start, start + 3, "A = String")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("make[A = String]", pointer.getElement.getText)
    assertEquals(
      "A = String",
      PsiTreeUtil.findChildOfType(file, classOf[MetallurgyTypeArguments]).namedTypeArguments.head.getText
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

  def testNamedChildReplacementAndDeletionKeepThePhysicalListConsistent(): Unit =
    val source      =
      """import scala.language.experimental.namedTypeArguments
        |val result = pair[A = Int, B = String](1, "text")
        |""".stripMargin
    val file        = physical("AppliedNamedTypeArguments3.scala", source)
    val donor       = physical(
      "AppliedNamedTypeArguments4.scala",
      "import scala.language.experimental.namedTypeArguments\nval donor = make[A = Long]\n"
    )
    val list        = PsiTreeUtil.findChildOfType(file, classOf[MetallurgyTypeArguments])
    val replacement = PsiTreeUtil
      .findChildOfType(donor, classOf[MetallurgyTypeArguments])
      .namedTypeArguments
      .head
      .typeElement
      .get
      .copy()

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val _ = list.namedTypeArguments.head.typeElement.get.replace(replacement)
    )
    assertEquals("[A = Long, B = String]", list.getText)
    assertTrue(list.typeArgs.isEmpty)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = list.namedTypeArguments.last.delete()
    )
    assertEquals("[A = Long]", list.getText)
    assertEquals(Vector("A = Long"), list.logicalTypeArguments.map(_.getText).toVector)

  def testMixedAndOrdinaryNamedTypeArgumentsFailClosedWithoutPartialPsi(): Unit =
    Vector(
      "import scala.language.experimental.namedTypeArguments\nval mixed = pair[Int, B = String](1, \"text\")\n",
      "import scala.language.experimental.namedTypeArguments\ntype Bad = F[A = Int]\n",
      "trait F[X[_]]\ntype Bad = F[List]\n",
      "def bad[A: Ordering](value: A): A = value\n",
      "type Bad = [X] =>> List[X]\n",
      "type Bad = (Int, String)\n",
      "type Bad = Int => String\n",
      "def bad(values: String*): Unit = ()\n",
      "import scala.language.experimental.namedTypeArguments\nval value = 1\nval bad = pair[A = Int](value, \"text\")\n",
      "import scala.language.experimental.namedTypeArguments\nval bad = target.make[A = Int]\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/AppliedNamedClosed${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      file.getChildren
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(source, failure.nonEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterizedTypeElement]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeArgs]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)

  def testTypeInferenceFixtureSourcesKeepMarkerAndRightTriviaOutsideTheExactExpression(): Unit =
    Vector(
      """import scala.language.experimental.namedTypeArguments
        |def make[A]: A = ???
        |val value = /*start*/make[A = Int]/*end*/
        |//Int
        |""".stripMargin -> "make[A = Int]",
      """import scala.language.experimental.namedTypeArguments
        |def pair[A, B](a: A, b: B): B = b
        |val value = /*start*/pair[A = Int](1, "text")/*end*/
        |//String
        |""".stripMargin -> "pair[A = Int](1, \"text\")"
    ).zipWithIndex.foreach { case ((source, expected), index) =>
      val file       = physical(s"AppliedNamedFixture${index + 1}.scala", source)
      val start      = source.indexOf(expected)
      val expression = PsiTreeUtil.findElementOfClassAtRange(
        file,
        start,
        start + expected.length,
        classOf[ScExpression]
      )
      assertEquals(expected, expression.getText)
    }

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

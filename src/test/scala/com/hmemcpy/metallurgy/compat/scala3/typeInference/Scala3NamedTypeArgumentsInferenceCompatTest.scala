package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.ScalaFileType
import org.junit.Assert.assertTrue

/** Verbatim port of the bundled Scala plugin's `Scala3NamedTypeArgumentsInferenceTest`. Snippets are kept exactly; only
  * the asserting helper differs, validating the same PSI data (`ScExpression.type`).
  */
final class Scala3NamedTypeArgumentsInferenceCompatTest extends Scala3CompatTestCase:

  private val START = "/*start*/"
  private val END   = "/*end*/"

  def testMethodInvocationWithPartiallyNamedTypeArguments_InferSecondParam(): Unit = assertExprType(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def pair[A, B](a: A, b: B): (A, B) = (a, b)
       |
       |val value = ${START}pair[A = Int](1, "text")$END
       |//(Int, String)
       |""".stripMargin
  )

  def testMethodInvocationWithPartiallyNamedTypeArguments_InferFirstParam(): Unit = assertExprType(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def pair[A, B](a: A, b: B): (A, B) = (a, b)
       |
       |val value = ${START}pair[B = String](1, "text")$END
       |//(Int, String)
       |""".stripMargin
  )

  def testGenericCallWithPartiallyNamedTypeArguments_InferSecondParamFromExpectedType(): Unit = assertExprType(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def make[A, B]: (A, B) = ???
       |
       |val value: (Int, String) = ${START}make[A = Int]$END
       |//(Int, String)
       |""".stripMargin
  )

  def testGenericCallWithPartiallyNamedTypeArguments_InferFirstParamFromExpectedType(): Unit = assertExprType(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def make[A, B]: (A, B) = ???
       |
       |val value: (Int, String) = ${START}make[B = String]$END
       |//(Int, String)
       |""".stripMargin
  )

  def testDocsExampleConstructWithNamedTypeArguments(): Unit = assertExprType(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def construct[Elem, Coll[_]](xs: Elem*): Coll[Elem] = ???
       |
       |val xs1 = construct[Coll = List, Elem = Int](1, 2, 3)
       |val xs2 = ${START}construct[Coll = List](1, 2, 3)$END
       |//List[Int]
       |""".stripMargin
  )

  def testCompilerRejectedConstructIsNotSuppressed(): Unit =
    // dotc rejects `Undefined` (not a type); the producer must not produce valid PSI for it. The first parse is the
    // pending placeholder (no errors), and once the backend reports the compiler error the file reloads to the
    // bundled parse, which keeps the error.
    val source = "def f[A] = ???\nval v = f[A = Undefined]\n"
    myFixture.configureByText(ScalaFileType.INSTANCE, source)
    awaitBackendPublished()
    import scala.jdk.CollectionConverters.*
    val errors =
      com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(getFile, classOf[com.intellij.psi.PsiErrorElement]).asScala
    assertTrue("a construct the compiler rejects keeps a parser error (not suppressed)", errors.nonEmpty)

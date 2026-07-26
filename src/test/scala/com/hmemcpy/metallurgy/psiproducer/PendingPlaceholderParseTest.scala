package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScMethodCall}
import org.junit.Assert.{assertFalse, assertTrue}

import scala.jdk.CollectionConverters.*

/** The dialect file-root parse must never show a bundled parse the compiler has not yet vouched for: the first parse of
  * a source the bundled parser cannot represent returns a pending placeholder (no error nodes, no Scala expression
  * structure), so the file is never painted red and never trips a bundled-PSI invariant while the compiler decides.
  */
final class PendingPlaceholderParseTest extends Scala3CompatTestCase:

  private val namedTypeArgsSource: String =
    """import scala.language.experimental.namedTypeArguments
      |
      |def pair[A, B](a: A, b: B): (A, B) = (a, b)
      |
      |val value = pair[A = Int](1, "text")
      |""".stripMargin

  // The first parse happens before the backend has decided; it must be the placeholder, not the bundled parse.
  def testFirstParseIsPendingPlaceholderWithoutErrors(): Unit =
    ProducerParseState.clear()
    DotcTreeSource.clear()
    myFixture.configureByText(ScalaFileType.INSTANCE, namedTypeArgsSource)
    val file = getFile.asInstanceOf[ScalaFile]
    assertFalse(
      "pending placeholder has no PsiErrorElement (never red before the compiler decides)",
      PsiTreeUtil.hasErrorElements(file)
    )
    assertTrue(
      "pending placeholder exposes no ScMethodCall (no None.get crash window)",
      PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.isEmpty
    )
    assertTrue(
      "pending placeholder exposes no ScGenericCall",
      PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).asScala.isEmpty
    )

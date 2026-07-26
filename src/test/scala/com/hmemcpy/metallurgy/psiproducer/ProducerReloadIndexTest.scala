package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.psi.{SmartPointerManager, SmartPsiElementPointer}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.junit.Assert.{assertNotNull, assertTrue}

import scala.jdk.CollectionConverters.*

/** The smart-pointer and PSI infrastructure must reflect the producer's tree after a controlled reload, not the bundled
  * parse that preceded it. (Stub-index and find-usages over producer declarations await class/object node coverage.)
  */
final class ProducerReloadIndexTest extends Scala3CompatTestCase:

  // The named-type-arguments usage leaves a parse error, so the bundled parse fragments; the backend then publishes and
  // reloads the file with the producer's tree, which maps the top-level defs.
  private val namedTypeArgsSource: String =
    """import scala.language.experimental.namedTypeArguments
      |
      |def make[A, B]: (A, B) = ???
      |def pair[A, B](a: A, b: B): (A, B) = (a, b)
      |val value = pair[A = Int](1, "text")
      |""".stripMargin

  def testProducerFunctionDefinitionSurvivesReload(): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, namedTypeArgsSource)
    awaitBackendPublished()
    val defs =
      PsiTreeUtil.findChildrenOfType(getFile.asInstanceOf[ScalaFile], classOf[ScFunctionDefinition]).asScala.toList
    assertTrue(
      s"producer-built ScFunctionDefinition present after reload (got ${defs.map(_.getName).mkString(", ")})",
      defs.exists(_.getName == "make")
    )

  def testSmartPointerToProducerDeclarationResolves(): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, namedTypeArgsSource)
    awaitBackendPublished()
    val file                               = getFile.asInstanceOf[ScalaFile]
    val target                             = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionDefinition])
      .asScala
      .find(_.getName == "make")
      .orNull
    assertNotNull("producer-built 'make' def found", target)
    val pointer: SmartPsiElementPointer[?] =
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(target)
    val resolved                           = pointer.getElement
    assertNotNull("smart pointer to a producer-built declaration resolves after the reload", resolved)
    assertTrue("resolved element is the same def", resolved eq target)

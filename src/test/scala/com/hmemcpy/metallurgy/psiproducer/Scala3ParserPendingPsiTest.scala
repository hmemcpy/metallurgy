package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.{DependentLanguage, InjectableLanguage}
import com.intellij.lang.jvm.JvmLanguage
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.{PsiErrorElement, PsiFileFactory}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

final class Scala3ParserPendingPsiTest extends com.intellij.testFramework.fixtures.BasePlatformTestCase:

  private val source =
    """import scala.language.experimental.namedTypeArguments
      |
      |def pair[A, B](a: A, b: B): (A, B) = (a, b)
      |val result = pair[A = Int](1, "text")
      |val unfinished = result.
      |""".stripMargin

  def testLanguageAndFileTypeAreUnrelatedToScala(): Unit =
    val language = Scala3ParserPendingLanguage.INSTANCE
    assertEquals(null, language.getBaseLanguage)
    assertFalse(classOf[JvmLanguage].isInstance(language))
    assertFalse(classOf[DependentLanguage].isInstance(language))
    assertFalse(classOf[InjectableLanguage].isInstance(language))
    assertSame(Scala3ParserPendingFileType.INSTANCE, language.getAssociatedFileType)

  def testPendingPsiIsVerbatimAndSemanticallyEmpty(): Unit =
    val file = PsiFileFactory
      .getInstance(getProject)
      .createFileFromText("Pending.scala", Scala3ParserPendingLanguage.INSTANCE, source)

    assertEquals(source, file.getText)
    assertSame(Scala3ParserPendingLanguage.INSTANCE, file.getLanguage)
    assertFalse(file.isInstanceOf[ScalaFile])
    assertFalse(file.getNode.getElementType.isInstanceOf[IStubFileElementType[?]])
    assertFalse(PsiTreeUtil.hasErrorElements(file))

    val elements = PsiTreeUtil.collectElements(file, _ => true)
    assertTrue(elements.forall(_.getReferences.isEmpty))
    assertTrue(elements.forall(!_.isInstanceOf[PsiErrorElement]))

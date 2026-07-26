package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.psi.{PsiFile, SingleRootFileViewProvider}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.ScFileViewProvider
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

/** A dialect file in an active module uses the Metallurgy-owned view provider, not the bundled (final)
  * ScFileViewProvider. The provider is the seam that lets Metallurgy control the file's content lifecycle.
  */
final class Scala3DotcFileViewProviderTest extends Scala3CompatTestCase:

  def testActiveModuleFileUsesMetallurgyViewProvider(): Unit =
    val file = myFixture.addFileToProject("ProviderProbe.scala", "class ProviderProbe\n")
    val vp   = file.getViewProvider
    assertTrue(
      s"active-module dialect file uses Scala3DotcFileViewProvider, got ${vp.getClass.getName}",
      vp.isInstanceOf[Scala3DotcFileViewProvider]
    )

  def testProviderIsNotBundledScFileViewProvider(): Unit =
    val file = myFixture.addFileToProject("NotBundledProbe.scala", "class NotBundledProbe\n")
    val vp   = file.getViewProvider
    assertTrue(
      s"dialect provider must not be the final bundled ScFileViewProvider, got ${vp.getClass.getName}",
      !vp.isInstanceOf[ScFileViewProvider]
    )

  def testCreateCopyReturnsMetallurgyProvider(): Unit =
    val file = myFixture.addFileToProject("CopyProbe.scala", "class CopyProbe\n")
    val vp   = file.getViewProvider.asInstanceOf[Scala3DotcFileViewProvider]
    val copy = vp.createCopy(file.getVirtualFile)
    assertTrue(
      s"createCopy returns Scala3DotcFileViewProvider, got ${copy.getClass.getName}",
      copy.isInstanceOf[Scala3DotcFileViewProvider]
    )
    assertEquals("copy preserves base language", vp.getBaseLanguage, copy.getBaseLanguage)

  def testCreateFileProducesDialectBoundScalaFile(): Unit =
    val file = myFixture.addFileToProject(
      "CreationProbe.scala",
      """class CreationProbe {
        |  def answer = 42
        |}""".stripMargin + "\n"
    )
    assertTrue("provider's file is a ScalaFile", file.isInstanceOf[ScalaFile])
    assertEquals("file language is the dialect", Scala3DotcLanguage.INSTANCE, file.getLanguage)
    val fn   = PsiTreeUtil.findChildOfType(file, classOf[ScFunctionDefinition])
    assertNotNull("createFile produces real Scala PSI (a function definition)", fn)

  def testCreateCopyFileParsesIntoScalaPsi(): Unit =
    val file = myFixture.addFileToProject(
      "CopyParseProbe.scala",
      """class CopyParseProbe {
        |  def amount = 7
        |}""".stripMargin + "\n"
    )
    val vp   = file.getViewProvider.asInstanceOf[Scala3DotcFileViewProvider]
    val copy = vp.createCopy(file.getVirtualFile)
    val psi  = copy.getPsi(vp.getBaseLanguage)
    assertNotNull("copy creates a PsiFile", psi)
    assertEquals("copy file language is the dialect", Scala3DotcLanguage.INSTANCE, psi.getLanguage)
    val fn   = PsiTreeUtil.findChildOfType(psi, classOf[ScFunctionDefinition])
    assertNotNull("copy's file parses into real Scala PSI", fn)

  def testProviderIsSingleRoot(): Unit =
    val file = myFixture.addFileToProject("SingleRootProbe.scala", "class SingleRootProbe\n")
    val vp   = file.getViewProvider
    assertTrue(
      s"dialect provider is a SingleRootFileViewProvider (matches bundled semantics), got ${vp.getClass.getName}",
      vp.isInstanceOf[SingleRootFileViewProvider]
    )

  def testFileCopyProducesParsableDialectFile(): Unit =
    val original = myFixture.addFileToProject(
      "CopyViaCopyProbe.scala",
      """class CopyViaCopyProbe {
        |  def tally = 99
        |}""".stripMargin + "\n"
    )
    val copy     = original.copy().asInstanceOf[PsiFile]
    assertEquals("copied file keeps the dialect language", Scala3DotcLanguage.INSTANCE, copy.getLanguage)
    assertTrue(
      "copied file uses the Metallurgy provider",
      copy.getViewProvider.isInstanceOf[Scala3DotcFileViewProvider]
    )
    val fn       = PsiTreeUtil.findChildOfType(copy, classOf[ScFunctionDefinition])
    assertNotNull("copied file parses into real Scala PSI", fn)

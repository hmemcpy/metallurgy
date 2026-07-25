package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.lang.Language
import com.intellij.psi.LanguageSubstitutors
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaLanguage
import org.junit.Assert.{assertEquals, assertNull, assertTrue}

/** The substitutor returns the dialect for a file in an active module and `null` otherwise, so the bundled substitutor
  * selects ordinary Scala 3 or Scala 2 for inactive modules. Exercises the substitutor's own decision on a real
  * module-backed file (the fixture pre-sets language on in-memory files, bypassing platform substitution).
  */
final class Scala3DotcLanguageSubstitutorTest extends Scala3CompatTestCase:

  private def decide(active: Boolean): Language =
    val file     = myFixture.addFileToProject("Foo.scala", "class Foo\n").getVirtualFile
    val settings = MetallurgySettings(getProject)
    if !active then settings.setEnabled(getModule, enabled = false)
    val result   = new Scala3DotcLanguageSubstitutor().getLanguage(file, getProject)
    settings.setEnabled(getModule, enabled = true)
    result

  def testActiveModuleSubstitutesToDialect(): Unit =
    assertEquals(Scala3DotcLanguage.INSTANCE, decide(active = true))

  def testInactiveModuleFallsThrough(): Unit =
    assertNull("inactive module returns null so the bundled substitutor runs", decide(active = false))

  def testDialectIsKindOfScala3(): Unit =
    assertTrue(Scala3DotcLanguage.INSTANCE.isKindOf(Scala3Language.INSTANCE))

  def testPlatformSubstitutionChainReturnsDialect(): Unit =
    val vf          = myFixture.addFileToProject("ChainProbe.scala", "class Foo\n").getVirtualFile
    val substituted = LanguageSubstitutors.getInstance.substituteLanguage(ScalaLanguage.INSTANCE, vf, getProject)
    assertEquals(
      "platform substitution chain returns the dialect for an active module",
      Scala3DotcLanguage.INSTANCE,
      substituted
    )

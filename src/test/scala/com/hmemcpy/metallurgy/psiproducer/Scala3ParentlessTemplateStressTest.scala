package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.psi.{PsiErrorElement, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.junit.Assert.{assertEquals, assertTrue}

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

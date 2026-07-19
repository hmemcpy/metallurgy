package org.jetbrains.plugins.scala.lang.completion3.base

import com.intellij.codeInsight.lookup.{LookupElement, LookupElementPresentation}
import com.intellij.testFramework.fixtures.{CodeInsightTestFixture, TestLookupElementPresentation}
import org.jetbrains.plugins.scala.ScalaFileType

/** Minimal completion fixture backported from the bundled plugin's idea261.x TestKit. */
final class ScalaCompletionTestFixture(fixture: CodeInsightTestFixture):

  def completeBasic(fileText: String): Seq[LookupElement] =
    fixture.configureByText(ScalaFileType.INSTANCE, fileText)
    Option(fixture.completeBasic()).toSeq.flatMap(_.toSeq)

/** Minimal presentation seam backported from the bundled plugin's idea261.x TestKit. Keep this deliberately small:
  * Metallurgy only needs the renderer used by completion assertions.
  */
object ScalaCompletionTestFixture:

  def createPresentation(lookup: LookupElement): LookupElementPresentation =
    TestLookupElementPresentation.renderReal(lookup)

package com.hmemcpy.metallurgy.feature.completion

import com.hmemcpy.metallurgy.pc.PcCompletion
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementBuilder, LookupElementDecorator}
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.plugins.scala.lang.completion3.base.ScalaCompletionTestFixture.createPresentation
import org.junit.Assert.{assertEquals, assertSame}

final class PcCompletionMergerTest extends BasePlatformTestCase:

  def testCompilerCompletionOverridesNativeDuplicate(): Unit =
    val native   =
      LookupElementBuilder
        .create("name")
        .withIcon(AllIcons.Nodes.Field)
        .withTypeText("Any")
    val compiler = PcCompletion("name", "name", Some(": String"))

    val merged = PcCompletionMerger.mergeLookupElements(Seq(compiler), Seq(native))

    assertEquals(1, merged.size)
    val presentation = createPresentation(merged.head)
    assertEquals("name", presentation.getItemText)
    assertEquals("String", presentation.getTypeText)
    assertEquals(null, presentation.getTailText)
    assertSame(AllIcons.Nodes.Field, presentation.getIcon)
    assertSame(native, merged.head.asInstanceOf[LookupElementDecorator[?]].getDelegate)

  def testCompilerMethodUsesNativeTailAndTypeFormatting(): Unit =
    val native   = LookupElementBuilder.create("selectDynamic").withTypeText("Any")
    val compiler = PcCompletion(
      "selectDynamic",
      "selectDynamic(name: String): Any",
      Some("(name: String): Any")
    )

    val merged       = PcCompletionMerger.mergeLookupElements(Seq(compiler), Seq(native))
    val presentation = createPresentation(merged.head)

    assertEquals("(name: String)", presentation.getTailText)
    assertEquals("Any", presentation.getTypeText)

  def testUnmatchedCompletionsFromBothSourcesArePreserved(): Unit =
    val native   = LookupElementBuilder.create("notifyAll")
    val compiler = PcCompletion("age", "age", Some("Int"))

    val merged = PcCompletionMerger.mergeLookupElements(Seq(compiler), Seq(native))

    assertEquals(Seq("notifyAll", "age"), merged.map(_.getLookupString))

  def testUnmatchedCompilerCompletionCarriesItsBackendPsiIdentity(): Unit =
    val compiler = PcCompletion("generated", "generated: Int", Some("Int"), Some("Owner.generated()."))
    val target   = PsiFileFactory
      .getInstance(getProject)
      .createFileFromText("Generated.scala", PlainTextLanguage.INSTANCE, "generated")

    val merged = PcCompletionMerger.mergeLookupElements(Seq(compiler), Seq.empty, _ => Some(target))

    assertEquals(1, merged.size)
    assertSame(target, merged.head.getPsiElement)

  def testOverloadsAreMergedOneToOne(): Unit =
    val native   = Seq(
      LookupElementBuilder.create("apply").withTailText("(index: Int)"),
      LookupElementBuilder.create("apply").withTailText("(name: String)")
    )
    val compiler = Seq(
      PcCompletion("apply", "apply(index: Int): String", Some("(index: Int): String")),
      PcCompletion("apply", "apply(name: String): Int", Some("(name: String): Int"))
    )

    val merged = PcCompletionMerger.mergeLookupElements(compiler, native)

    assertEquals(2, merged.size)
    assertEquals(Seq("String", "Int"), merged.map(createPresentation).map(_.getTypeText))

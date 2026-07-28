package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.{PlatformTestUtil, ServiceContainerUtil}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3ParserPhysicalActivationTest extends Scala3CompatTestCase:

  def testPhysicalFileChangesFromNeutralToReadyInOneBatch(): Unit =
    val installed = Scala3ParserPreparationLifecycle.get(getProject)
    installed.dispose()

    val preparer   = new DeferredPreparer
    var files      = Vector.empty[VirtualFile]
    val activation = new PlatformRecordingActivation(getProject)
    val lifecycle  = new Scala3ParserPreparationLifecycle(
      getProject,
      preparer,
      _ => files,
      activation
    )
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[Scala3ParserPreparationLifecycle],
      lifecycle,
      getTestRootDisposable
    )

    val epoch    = lifecycle.prepare(getModule)
    val pending  = myFixture.addFileToProject(
      "src/Transition.scala",
      """import scala.language.experimental.namedTypeArguments
        |def choose[A](value: A): A = value
        |val result = choose[A = Int](1)
        |""".stripMargin
    )
    val file     = pending.getVirtualFile
    files = Vector(file)
    val document = FileDocumentManager.getInstance.getDocument(file)
    val marker   = document.createRangeMarker(0, "import".length)

    assertSame(Scala3ParserPendingLanguage.INSTANCE, pending.getLanguage)
    assertFalse(pending.isInstanceOf[ScalaFile])
    assertEquals(document.getText, pending.getText)
    val _                = myFixture.openFileInEditor(file)
    val semanticFindings = myFixture
      .doHighlighting()
      .asScala
      .filter(info => info.getSeverity == HighlightSeverity.ERROR || info.getSeverity == HighlightSeverity.WARNING)
    assertTrue(s"pending PSI has semantic findings: $semanticFindings", semanticFindings.isEmpty)

    preparer.complete(0, new TestParserBridge)
    PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

    val ready = PsiManager.getInstance(getProject).findFile(file)
    assertEquals(ParserPreparationState.Ready(epoch), lifecycle.stateFor(getModule))
    assertEquals(1, activation.batchCount)
    assertFalse(pending.isValid)
    assertTrue(ready.isInstanceOf[ScalaFile])
    assertSame(Scala3DotcLanguage.INSTANCE, ready.getLanguage)
    assertSame(document, FileDocumentManager.getInstance.getDocument(file))
    assertTrue(marker.isValid)
    assertEquals("import", document.getText(marker.getTextRange))

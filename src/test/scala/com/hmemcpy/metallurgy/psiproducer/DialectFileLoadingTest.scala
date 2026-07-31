package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScExportStmt, ScImportSelector}
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.junit.Assert.{assertEquals, assertFalse, assertNull, assertTrue}

import scala.jdk.CollectionConverters.*

/** A `.scala` file in an active Metallurgy module loads as the dialect, its supported syntax is stub-indexed, and an
  * inactive module stays on the bundled Scala 3. Exercises real file loading (a physical module file), not an in-memory
  * copy.
  */
final class DialectFileLoadingTest extends Scala3CompatTestCase:

  def testActiveModuleScalaFileLoadsAsDialect(): Unit =
    val file = myFixture.addFileToProject("Foo.scala", "class Foo\n")
    assertEquals("active-module .scala loads as the dialect", Scala3DotcLanguage.INSTANCE, file.getLanguage)

  def testClosedDialectFileResolvesFromTheStubIndexWithoutLoadingItsAst(): Unit =
    val file           = myFixture.addFileToProject(
      "Bar.scala",
      "export scala.Predef.{identity as exportedIdentity}\nimport a.b.{Original as Alias}\nexport scala.Predef.assert\n"
    )
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    val implementation = file.asInstanceOf[PsiFileImpl]
    assertFalse(
      "the indexed file must remain closed",
      FileEditorManager.getInstance(getProject).isFileOpen(file.getVirtualFile)
    )
    assertNull("the AST must be absent before the index query", implementation.getTreeElement)

    val found          = StubIndex
      .getElements(
        ScalaIndexKeys.ALIASED_IMPORT_KEY,
        "Original",
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScImportSelector]
      )
    assertTrue("an aliased import in a dialect file is stub-indexed and resolvable", !found.isEmpty)
    val navigationFile = Option(found.iterator().next()).map(_.getNavigationElement.getContainingFile.getVirtualFile)
    assertEquals("stub navigation must target the physical source", Some(file.getVirtualFile), navigationFile)
    val exports        = StubIndex
      .getElements(
        ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY,
        "",
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScExportStmt]
      )
    assertEquals("both default-package exports must be stub-indexed", 2, exports.size())
    val exportFiles    = exports.iterator().asScala.map(_.getNavigationElement.getContainingFile.getVirtualFile).toSet
    assertEquals("export stub navigation must target the physical source", Set(file.getVirtualFile), exportFiles)
    assertNull("stub-index lookup must not load the file AST", implementation.getTreeElement)

  def testInactiveModuleLoadsAsBundledScala3(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    try
      val file = myFixture.addFileToProject("Baz.scala", "class Baz\n")
      assertEquals("inactive module stays on bundled Scala 3", Scala3Language.INSTANCE, file.getLanguage)
    finally MetallurgySettings(getProject).setEnabled(getModule, enabled = true)

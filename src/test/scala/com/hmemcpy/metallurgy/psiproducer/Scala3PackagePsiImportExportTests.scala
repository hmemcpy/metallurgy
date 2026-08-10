package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParameterizedTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSimpleTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScExportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportSelector
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.psi.stubs.ScExportStmtStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportExprStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportSelectorStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportSelectorsStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportStmtStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScPackagingStub
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}
import scala.jdk.CollectionConverters.*

private[psiproducer] trait Scala3PackagePsiImportExportTests extends Scala3PackagePsiProducerTestSupport:
  self: Scala3PackagePsiPackageLifecycleTests =>

  def testReadyPhysicalImportsUseNativePsiAndReparseAndStubs(): Unit =
    val source            =
      """import a.b.c
        |import a.b.*
        |import a.b.{c}
        |import a.b.{c /* as */ as d}
        |import a.b.{c /* => */ => d}
        |import a.b.given
        |import a.b.given T
        |import a.b.{given, given T, *}
        |import a.b.++
        |import a.b.foo_+
        |import a.b.empty_?
        |import a.b.op_🚀
        |import a.b.`back-tick`
        |""".stripMargin
    val pending           = codeInsightFixture.addFileToProject("src/ImportCase.scala", source)
    val file              = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val _                 = codeInsightFixture.openFileInEditor(pending.getVirtualFile)
    val highlightingLexer = new Scala3ParserSyntaxHighlighterFactory()
      .getSyntaxHighlighter(getProject, pending.getVirtualFile)
      .getHighlightingLexer
    highlightingLexer.start("given")
    assertEquals(ScalaTokenType.GivenKeyword, highlightingLexer.getTokenType)
    assertImports(file, source, "c")
    assertImports(file.copy().asInstanceOf[com.intellij.psi.PsiFile], source, "c")
    val statementPointer  = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(PsiTreeUtil.findChildOfType(file, classOf[ScImportStmt]))

    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(11, 12, "changed")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertImports(reparsed, source.replaceFirst("a\\.b\\.c", "a.b.changed"), "changed")
    assertNotNull(statementPointer.getElement)
    assertEquals("import a.b.changed", statementPointer.getElement.getText)

    val groupedSource  = "package example\nimport a.b, c.d\nimport e.f\n"
    val groupedPending = codeInsightFixture.addFileToProject("src/PackageGroupedImportsCase.scala", groupedSource)
    val groupedFile    = PsiManager.getInstance(getProject).findFile(groupedPending.getVirtualFile)
    assertPackageGroupedImports(groupedFile, groupedSource, "c.d")
    assertPackageGroupedImports(groupedFile.copy().asInstanceOf[com.intellij.psi.PsiFile], groupedSource, "c.d")

    val groupedDocument = FileDocumentManager.getInstance.getDocument(groupedPending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          groupedDocument.replaceString(
            groupedSource.indexOf("c.d"),
            groupedSource.indexOf("c.d") + 3,
            "changed.d"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(groupedDocument)
    assertPackageGroupedImports(
      PsiManager.getInstance(getProject).findFile(groupedPending.getVirtualFile),
      groupedSource.replace("c.d", "changed.d"),
      "changed.d"
    )

    val legacySource  = "import a.b._\nimport a.b.{c as _}\nimport a.b.{c => _}\n"
    val legacyPending = codeInsightFixture.addFileToProject("src/LegacyAndHiddenImportsCase.scala", legacySource)
    val legacyFile    = PsiManager.getInstance(getProject).findFile(legacyPending.getVirtualFile)
    assertLegacyAndHiddenImports(legacyFile, legacySource)
    assertLegacyAndHiddenImports(legacyFile.copy().asInstanceOf[com.intellij.psi.PsiFile], legacySource)
    assertRecursiveStablePathsPreserveNativePsiPersistenceAndEditIdentity()
    assertReadyPhysicalExportsUseNativePsiPersistenceIndexesAndReparse()

  private def assertRecursiveStablePathsPreserveNativePsiPersistenceAndEditIdentity(): Unit =
    val packageSource   = "package alpha.beta.gamma.delta\n"
    val packagePending  = codeInsightFixture.addFileToProject("src/RecursivePackageCase.scala", packageSource)
    val packageFile     = PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile)
    assertSame(packageFile, PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile))
    assertPackagePath(packageFile, packageSource, Vector("alpha", "beta", "gamma", "delta"), persistence = true)
    assertPackagePath(
      packageFile.copy().asInstanceOf[com.intellij.psi.PsiFile],
      packageSource,
      Vector("alpha", "beta", "gamma", "delta"),
      persistence = true
    )
    val packagePrefix   = PsiTreeUtil
      .findChildrenOfType(packageFile, classOf[ScStableCodeReference])
      .asScala
      .find(_.getText == "alpha.beta")
      .get
    val packagePointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(packagePrefix)
    val packageDocument = FileDocumentManager.getInstance.getDocument(packagePending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = packageDocument.insertString(packageSource.length - 1, ".epsilon.zeta")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(packageDocument)
    val increased       = PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile)
    assertPackagePath(
      increased,
      "package alpha.beta.gamma.delta.epsilon.zeta\n",
      Vector("alpha", "beta", "gamma", "delta", "epsilon", "zeta"),
      persistence = true
    )
    assertEquals("alpha.beta", packagePointer.getElement.getText)
    assertSame(packagePointer.getElement, packagePointer.getElement.getNavigationElement)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = packageDocument.setText("package alpha.beta\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(packageDocument)
    val decreased = PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile)
    assertPackagePath(decreased, "package alpha.beta\n", Vector("alpha", "beta"), persistence = true)
    assertEquals("alpha.beta", packagePointer.getElement.getText)
    assertSame(packagePointer.getElement, packagePointer.getElement.getNavigationElement)

    val namedSource  = "package alpha.`match`.δ.++\n"
    val namedPending = codeInsightFixture.addFileToProject("src/NamedRecursivePackageCase.scala", namedSource)
    val namedFile    = PsiManager.getInstance(getProject).findFile(namedPending.getVirtualFile)
    assertPackagePath(namedFile, namedSource, Vector("alpha", "`match`", "δ", "++"), persistence = true)

    val importSource   =
      """import packet.alpha.`match`.δ.deep.ordinary
        |import packet.alpha.`match`.δ.deep.*
        |import packet.alpha.`match`.δ.deep.ordinary as renamed
        |import packet.alpha.`match`.δ.deep.{ordinary}
        |import packet.alpha.`match`.δ.deep.{ordinary as alias}
        |import packet.alpha.`match`.δ.deep.{ordinary => legacy}
        |import packet.alpha.`match`.δ.deep.given
        |import packet.alpha.`match`.δ.deep.given Box[Int]
        |import packet.alpha.`match`.δ.deep.{given, given Box[Int], *}
        |import packet.alpha.`match`.δ.deep.++
        |import packet.alpha.`match`.δ.deep.λ
        |import packet.alpha.`match`.δ.deep.`back-tick`
        |""".stripMargin
    val importPending  = codeInsightFixture.addFileToProject("src/RecursiveImportCase.scala", importSource)
    val importFile     = PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile)
    val qualifier      = Vector("packet", "alpha", "`match`", "δ", "deep")
    assertSame(importFile, PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile))
    assertImportPaths(importFile, importSource, qualifier)
    assertImportPaths(importFile.copy().asInstanceOf[com.intellij.psi.PsiFile], importSource, qualifier)
    val importPrefix   = PsiTreeUtil
      .findChildrenOfType(importFile, classOf[ScStableCodeReference])
      .asScala
      .find(_.getText == "packet.alpha")
      .get
    val importPointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(importPrefix)
    val importDocument = FileDocumentManager.getInstance.getDocument(importPending.getVirtualFile)
    val increasedText  = importSource.replace("packet.alpha.`match`.δ.deep", "packet.alpha.`match`.δ.deep.more")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = importDocument.setText(increasedText)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(importDocument)
    assertImportPaths(
      PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile),
      increasedText,
      qualifier :+ "more"
    )
    assertEquals("packet.alpha", importPointer.getElement.getText)
    assertSame(importPointer.getElement, importPointer.getElement.getNavigationElement)

    val decreasedText = increasedText.replace("packet.alpha.`match`.δ.deep.more", "packet.alpha.deep")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = importDocument.setText(decreasedText)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(importDocument)
    assertImportPaths(
      PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile),
      decreasedText,
      Vector("packet", "alpha", "deep")
    )
    assertEquals("packet.alpha", importPointer.getElement.getText)
    assertSame(importPointer.getElement, importPointer.getElement.getNavigationElement)

    val adjacentSource  = "package alpha.beta.gamma.delta\n\nfinal class Adjacent\n"
    val adjacentPending = codeInsightFixture.addFileToProject("src/AdjacentRecursivePackageCase.scala", adjacentSource)
    val adjacentFile    = PsiManager.getInstance(getProject).findFile(adjacentPending.getVirtualFile)
    assertEquals(adjacentSource, adjacentFile.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[ScPackaging]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[ScTypeDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[PsiErrorElement]).isEmpty)
    val failure         = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(adjacentPending.getVirtualFile, ParserSyntaxSnapshot.digest(adjacentSource))
    assertTrue(failure.toString, failure.nonEmpty)
    assertEquals(Scala3SyntaxCapabilityStage.Catalog, failure.get.stage)
    assertTrue(adjacentFile.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)

  private def assertReadyPhysicalExportsUseNativePsiPersistenceIndexesAndReparse(): Unit =
    val source     =
      """package exportcase; /* header trivia */
        |import scala.math.Ordering
        |export scala.Predef.identity
        |export scala.collection.immutable.List.apply
        |export scala.Predef.*
        |export scala.Predef.{assert, identity}
        |export scala.Predef.{identity as renamedIdentity}
        |export scala.Predef.{identity => legacyIdentity}
        |export scala.Predef.{assert as _, *}
        |export scala.math.Ordering.given
        |export scala.Predef.{given DummyImplicit}
        |export scala.math.Ordering.{given Ordering[Int]}
        |export scala.Predef.identity
        |""".stripMargin
    val pending    = codeInsightFixture.addFileToProject("src/ExportCase.scala", source)
    val file       = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertExports(file, source, "exportcase", 11)
    assertExports(file.copy().asInstanceOf[com.intellij.psi.PsiFile], source, "exportcase", 11)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertEquals(
      Vector(pending.getVirtualFile),
      indexedExports("exportcase").map(_.getContainingFile.getVirtualFile).distinct
    )
    val aliasFiles = StubIndex
      .getElements(
        ScalaIndexKeys.ALIASED_IMPORT_KEY,
        "identity",
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScImportSelector]
      )
      .asScala
      .map(_.getContainingFile.getVirtualFile)
      .toSet
    assertTrue(aliasFiles.contains(pending.getVirtualFile))
    val first      = PsiTreeUtil.findChildOfType(file, classOf[ScExportStmt])
    val pointer    = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(first)
    val document   = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    val direct     = "export scala.Predef.identity"
    val braced     = "export scala.Predef.{identity, assert}"
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf(direct)
          document.replaceString(start, start + direct.length, braced)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val bracedText = source.replaceFirst(direct, braced)
    val reparsed   = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertExports(reparsed, bracedText, "exportcase", 11)
    assertNotNull(pointer.getElement)
    assertEquals(braced, pointer.getElement.getText)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf("identity, assert")
          document.replaceString(start, start + "identity, assert".length, "assert")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val selectorChanged = bracedText.replaceFirst("identity, assert", "assert")
    assertExports(
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile),
      selectorChanged,
      "exportcase",
      11
    )

    val defaultSource  = "export scala.Predef.identity\nexport scala.Predef.{assert as exposedAssert}\n"
    val defaultPending = codeInsightFixture.addFileToProject("src/DefaultExportCase.scala", defaultSource)
    val defaultFile    = PsiManager.getInstance(getProject).findFile(defaultPending.getVirtualFile)
    assertExports(defaultFile, defaultSource, "", 2)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertEquals(
      Vector(defaultPending.getVirtualFile),
      indexedExports("").map(_.getContainingFile.getVirtualFile).distinct
    )

  private def assertExports(
      file: com.intellij.psi.PsiFile,
      text: String,
      packageName: String,
      expectedStatements: Int
  ): Unit =
    assertEquals(text, file.getText)
    val leaves                                                                  = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements                                                              = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
    val expressions                                                             = statements.flatMap(_.importExprs)
    val capability                                                              = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertTrue(capability.toString, capability.isEmpty)
    assertEquals(expectedStatements, statements.size)
    assertEquals(expectedStatements, expressions.size)
    assertTrue(statements.forall(_.isTopLevel))
    assertTrue(statements.forall(_.topLevelQualifier == Some(packageName)))
    assertEquals(
      Vector.fill(expectedStatements)("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.imports.ScExportStmtImpl"),
      statements.map(_.getClass.getName)
    )
    statements.foreach: statement =>
      assertEquals(ScalaElementType.ExportStatement, statement.getNode.getElementType)
      assertSame(file, statement.getContainingFile)
      assertSame(getProject, statement.getProject)
      assertSame(statement, statement.getNode.getPsi)
      assertSame(statement, statement.getNavigationElement)
      assertEquals(
        statement.importExprs,
        statement.getChildren.toVector.collect {
          case expression: org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportExpr => expression
        }
      )
    statements.zip(expressions).foreach((statement, expression) => assertSame(statement, expression.getParent))
    expressions.foreach: expression =>
      expression.reference.foreach: reference =>
        assertSame(expression, reference.getParent)
        var current = Option(reference)
        while current.nonEmpty do
          val value = current.get
          assertEquals(ScalaElementType.REFERENCE, value.getNode.getElementType)
          value.qualifier.foreach(qualifier => assertSame(value, qualifier.getParent))
          current = value.qualifier
    val selectorSets                                                            = expressions.flatMap(_.selectorSet)
    val selectors                                                               = expressions.flatMap(_.selectors)
    selectorSets.foreach(set => assertTrue(expressions.contains(set.getParent)))
    selectors.foreach: selector =>
      assertTrue(selectorSets.contains(selector.getParent))
      assertSame(selector.parentImportExpression, selector.getParent.getParent)
    assertTrue(selectors.exists(_.isAliasedImport))
    val stableReferences                                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScStableCodeReference]).asScala.toVector
    val simpleTypes                                                             = PsiTreeUtil.findChildrenOfType(file, classOf[ScSimpleTypeElement]).asScala.toVector
    val appliedTypes                                                            = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterizedTypeElement]).asScala.toVector
    val composites                                                              = statements ++ expressions ++ selectorSets ++ selectors ++ stableReferences ++ simpleTypes ++
      appliedTypes
    composites.foreach: element =>
      assertEquals(
        element.getText,
        text.substring(element.getTextRange.getStartOffset, element.getTextRange.getEndOffset)
      )
      assertSame(file, element.getContainingFile)
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    if packageName.nonEmpty then
      assertTrue(expressions.exists(_.hasWildcardSelector))
      assertTrue(expressions.exists(_.hasGivenSelector))
      assertTrue(selectors.exists(_.isWildcardSelector))
      assertTrue(selectors.exists(_.isGivenSelector))
      assertTrue(simpleTypes.exists(_.getText == "DummyImplicit"))
      assertTrue(appliedTypes.exists(_.getText == "Ordering[Int]"))
    val stubs                                                                   = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    def hasExportAncestor(stub: com.intellij.psi.stubs.StubElement[?]): Boolean =
      var parent = stub.getParentStub
      while parent != null && !parent.isInstanceOf[ScExportStmtStub] do parent = parent.getParentStub
      parent != null
    val statementStubs                                                          = stubs.collect { case value: ScExportStmtStub => value }
    val expressionStubs                                                         = stubs.collect { case value: ScImportExprStub if hasExportAncestor(value) => value }
    val selectorSetStubs                                                        = stubs.collect { case value: ScImportSelectorsStub if hasExportAncestor(value) => value }
    val selectorStubs                                                           = stubs.collect { case value: ScImportSelectorStub if hasExportAncestor(value) => value }
    assertEquals(expectedStatements, statementStubs.size)
    assertEquals(expectedStatements, expressionStubs.size)
    assertEquals(selectorSets.size, selectorSetStubs.size)
    assertEquals(selectors.size, selectorStubs.size)
    assertTrue(statementStubs.forall(_.isTopLevel))
    assertTrue(statementStubs.forall(_.topLevelQualifier == Some(packageName)))
    assertEquals(statements.map(_.getText), statementStubs.map(_.importText).toVector)
    assertTrue(selectorStubs.exists(_.isAliasedImport))
    if packageName.nonEmpty then
      assertTrue(expressionStubs.exists(_.hasWildcardSelector))
      assertTrue(expressionStubs.exists(_.hasGivenSelector))
      assertTrue(selectorStubs.exists(_.isGivenSelector))

    val enumerator                                          = new TestStringEnumerator
    def bytes(write: StubOutputStream => Unit): Array[Byte] =
      val sink   = new ByteArrayOutputStream
      val output = new StubOutputStream(sink, enumerator)
      write(output)
      output.flush()
      sink.toByteArray
    val statementCopy                                       = ScalaElementType.ExportStatement.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.ExportStatement.serialize(statementStubs.head, _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(statementStubs.head.importText, statementCopy.importText)
    assertEquals(statementStubs.head.isTopLevel, statementCopy.isTopLevel)
    assertEquals(statementStubs.head.topLevelQualifier, statementCopy.topLevelQualifier)
    val indexedPackages                                     = Vector.newBuilder[String]
    statementStubs.foreach: stub =>
      ScalaElementType.ExportStatement.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY, _indexKey)
            indexedPackages += value.toString
      )
    assertEquals(Vector.fill(expectedStatements)(packageName), indexedPackages.result())
    val indexedAliases                                      = Vector.newBuilder[String]
    selectorStubs.foreach: stub =>
      ScalaElementType.IMPORT_SELECTOR.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.ALIASED_IMPORT_KEY, _indexKey)
            indexedAliases += value.toString
      )
    assertTrue(indexedAliases.result().nonEmpty)

    Option(PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])) match
      case Some(packaging) =>
        assertEquals(packageName, packaging.packageName)
        statements.foreach(statement => assertSame(packaging, statement.getParent))
        val topStatements = packaging.getChildren.toVector.collect {
          case statement: ScImportStmt => statement.getText
          case statement: ScExportStmt => statement.getText
        }
        assertEquals("import scala.math.Ordering", topStatements.head)
        assertEquals(statements.map(_.getText), topStatements.tail)
      case None            => statements.foreach(statement => assertSame(file, statement.getParent))

  private def assertImportPaths(
      file: com.intellij.psi.PsiFile,
      text: String,
      qualifierSegments: Vector[String]
  ): Unit =
    assertEquals(text, file.getText)
    val leaves         = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements     = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions    = statements.flatMap(_.importExprs)
    val qualifierText  = qualifierSegments.mkString(".")
    val finalNames     = Vector(
      Some("ordinary"),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Some("++"),
      Some("λ"),
      Some("`back-tick`")
    )
    val referenceTexts = finalNames.map(_.fold(qualifierText)(name => s"$qualifierText.$name"))
    assertEquals(12, statements.size)
    assertEquals(12, expressions.size)
    assertEquals(referenceTexts, expressions.flatMap(_.reference).map(_.getText))
    expressions
      .zip(finalNames)
      .foreach: (expression, finalName) =>
        val reference = expression.reference.get
        assertStablePath(reference, qualifierSegments ++ finalName)
        assertSame(expression, reference.getParent)
    statements.zip(expressions).foreach((statement, expression) => assertSame(statement, expression.getParent))
    val selectorSets   = expressions.flatMap(_.selectorSet)
    val selectors      = expressions.flatMap(_.selectors)
    assertEquals(7, selectorSets.size)
    assertEquals(9, selectors.size)
    assertEquals(3, selectors.count(_.isAliasedImport))
    assertEquals(4, selectors.count(_.isGivenSelector))
    assertEquals(1, selectors.count(_.isWildcardSelector))
    assertEquals(Vector("Box[Int]", "Box[Int]"), selectors.flatMap(_.givenTypeElement).map(_.getText))
    val capability     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertTrue(capability.toString, capability.isEmpty)

    val stubs           = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    val expressionStubs = stubs.collect { case value: ScImportExprStub => value }
    val selectorStubs   = stubs.collect { case value: ScImportSelectorStub => value }
    assertEquals(12, stubs.count(_.isInstanceOf[ScImportStmtStub]))
    assertEquals(12, expressionStubs.size)
    assertEquals(7, stubs.count(_.isInstanceOf[ScImportSelectorsStub]))
    assertEquals(9, selectorStubs.size)
    assertEquals(referenceTexts.map(Some(_)), expressionStubs.map(_.referenceText).toVector)
    val enumerator      = new TestStringEnumerator
    val sink            = new ByteArrayOutputStream
    val output          = new StubOutputStream(sink, enumerator)
    ScalaElementType.IMPORT_EXPR.serialize(expressionStubs(8), output)
    output.flush()
    val expressionCopy  = ScalaElementType.IMPORT_EXPR.deserialize(
      new StubInputStream(new ByteArrayInputStream(sink.toByteArray), enumerator),
      new PsiFileStubImpl(null)
    )
    assertEquals(expressionStubs(8).referenceText, expressionCopy.referenceText)
    assertEquals(expressionStubs(8).hasWildcardSelector, expressionCopy.hasWildcardSelector)
    assertEquals(expressionStubs(8).hasGivenSelector, expressionCopy.hasGivenSelector)
    val indexed         = Vector.newBuilder[String]
    selectorStubs.foreach: stub =>
      ScalaElementType.IMPORT_SELECTOR.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.ALIASED_IMPORT_KEY, _indexKey)
            indexed += value.toString
      )
    assertEquals(Vector.fill(4)("ordinary"), indexed.result())

  private def assertPackageGroupedImports(
      file: com.intellij.psi.PsiFile,
      text: String,
      secondExpression: String
  ): Unit =
    assertEquals(text, file.getText)
    assertEquals(text, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging   = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val statements  = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions = statements.flatMap(_.importExprs)
    val capability  = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertNotNull(capability.map(_.toString).orNull, packaging)
    assertEquals(Vector("import a.b, " + secondExpression, "import e.f"), statements.map(_.getText))
    assertEquals(Vector("a.b", secondExpression, "e.f"), expressions.map(_.getText))
    assertEquals(Vector(2, 1), statements.map(_.importExprs.size))
    statements.foreach(statement => assertSame(packaging, statement.getParent))
    statements
      .zip(Vector(expressions.take(2), expressions.drop(2)))
      .foreach: (statement, children) =>
        children.foreach(expression => assertSame(statement, expression.getParent))
    (Vector(packaging) ++ statements ++ expressions).foreach: element =>
      assertSame(file, element.getContainingFile)
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    val stubs       = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    assertEquals(1, stubs.count(_.isInstanceOf[ScPackagingStub]))
    assertEquals(2, stubs.count(_.isInstanceOf[ScImportStmtStub]))
    assertEquals(3, stubs.count(_.isInstanceOf[ScImportExprStub]))

  private def assertImports(file: com.intellij.psi.PsiFile, text: String, plainName: String): Unit =
    assertEquals(text, file.getText)
    val leaves         = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements     = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions    = statements.flatMap(_.importExprs)
    val selectorSets   = expressions.flatMap(_.selectorSet)
    val selectors      = selectorSets.flatMap(_.selectors)
    val failure        =
      Scala3SyntaxCapabilityService.get(getProject).failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(13, statements.size)
    assertEquals(13, expressions.size)
    assertEquals(6, selectorSets.size)
    assertEquals(8, selectors.size)
    assertEquals(
      Vector(
        "{c}",
        "{c /* as */ as d}",
        "{c /* => */ => d}",
        "given",
        "given T",
        "{given, given T, *}"
      ),
      selectorSets.map(_.getText)
    )
    assertEquals(
      Vector(
        s"a.b.$plainName",
        "a.b.*",
        "a.b.{c}",
        "a.b.{c /* as */ as d}",
        "a.b.{c /* => */ => d}",
        "a.b.given",
        "a.b.given T",
        "a.b.{given, given T, *}",
        "a.b.++",
        "a.b.foo_+",
        "a.b.empty_?",
        "a.b.op_🚀",
        "a.b.`back-tick`"
      ),
      expressions.map(_.getText)
    )
    assertEquals(
      Vector("++", "foo_+", "empty_?", "op_🚀", "`back-tick`"),
      expressions.takeRight(5).flatMap(_.reference).map(_.refName)
    )
    val plain          = expressions.head
    val wildcard       = expressions(1)
    val boundedGiven   = expressions(6)
    val mixed          = expressions(7)
    assertEquals(Some(s"a.b.$plainName"), plain.reference.map(_.getText))
    assertEquals(Some("a.b"), plain.qualifier.map(_.getText))
    assertTrue(plain.selectors.isEmpty)
    assertFalse(plain.hasWildcardSelector)
    assertFalse(plain.hasGivenSelector)
    assertEquals(Some("a.b"), wildcard.reference.map(_.getText))
    assertEquals(Some("a.b"), wildcard.qualifier.map(_.getText))
    assertTrue(wildcard.selectorSet.isEmpty)
    assertTrue(wildcard.hasWildcardSelector)
    assertEquals(Some("*"), wildcard.wildcardElement.map(_.getText))
    assertTrue(boundedGiven.hasGivenSelector)
    assertTrue(mixed.hasGivenSelector)
    assertTrue(mixed.hasWildcardSelector)
    assertEquals(Some("*"), mixed.wildcardElement.map(_.getText))
    val aliases        = selectors.filter(_.isAliasedImport)
    val givenSelectors = selectors.filter(_.isGivenSelector)
    val wildcardInSet  = selectors.filter(_.isWildcardSelector)
    assertEquals(2, aliases.size)
    assertEquals(Vector(Some("d"), Some("d")), aliases.map(_.importedName))
    assertEquals(Vector(Some("d"), Some("d")), aliases.map(_.aliasName))
    assertEquals(Vector("c", "c"), aliases.flatMap(_.reference).map(_.getText))
    assertTrue(aliases.head.isScala3StyleAliasImport)
    assertTrue(aliases(1).isScala2StyleAliasImport)
    val bindings       = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    val asOffset       = aliases.head.getText.lastIndexOf("as")
    val asLeaf         = aliases.head.getNode.findLeafElementAt(asOffset)
    assertEquals("as", asLeaf.getText)
    assertEquals(bindings.elementTypes(NativePsiElementBindings.ImportAliasAsTokenSurface), asLeaf.getElementType)
    assertEquals(
      TextRange(
        aliases.head.getTextRange.getStartOffset + asOffset,
        aliases.head.getTextRange.getStartOffset + asOffset + 2
      ),
      asLeaf.getTextRange
    )
    val arrowOffset    = aliases(1).getText.lastIndexOf("=>")
    val arrowLeaf      = aliases(1).getNode.findLeafElementAt(arrowOffset)
    assertEquals("=>", arrowLeaf.getText)
    assertEquals(bindings.elementTypes(NativePsiElementBindings.ImportAliasArrowTokenSurface), arrowLeaf.getElementType)
    assertEquals(
      TextRange(
        aliases(1).getTextRange.getStartOffset + arrowOffset,
        aliases(1).getTextRange.getStartOffset + arrowOffset + 2
      ),
      arrowLeaf.getTextRange
    )
    assertEquals(4, givenSelectors.size)
    assertEquals(Vector("T", "T"), givenSelectors.flatMap(_.givenTypeElement).map(_.getText))
    assertEquals(1, wildcardInSet.size)
    assertEquals(Some("*"), wildcardInSet.head.wildcardElement.map(_.getText))
    selectors.foreach(selector =>
      assertSame(selector.parentImportExpression, selectorSets.find(_.selectors.contains(selector)).get.getParent)
    )
    statements.zip(expressions).foreach((statement, expression) => assertSame(statement, expression.getParent))
    selectorSets.foreach(set => assertTrue(expressions.contains(set.getParent)))
    selectors.foreach(selector => assertTrue(selectorSets.contains(selector.getParent)))
    val simpleTypes    = PsiTreeUtil.findChildrenOfType(file, classOf[ScSimpleTypeElement]).asScala.toVector
    assertEquals(Vector("T", "T"), simpleTypes.map(_.getText))
    simpleTypes.foreach: simpleType =>
      val reference = PsiTreeUtil.findChildOfType(simpleType, classOf[ScStableCodeReference])
      assertNotNull(reference)
      assertSame(simpleType, reference.getParent)
      assertTrue(givenSelectors.contains(simpleType.getParent))
    val composites     = statements ++ expressions ++ selectorSets ++ selectors ++ simpleTypes ++
      PsiTreeUtil.findChildrenOfType(file, classOf[ScStableCodeReference]).asScala
    composites.foreach: element =>
      assertSame(file, element.getContainingFile)
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)

    val stubTree                                            = file.asInstanceOf[PsiFileImpl].calcStubTree
    val stubs                                               = stubTree.getPlainList.asScala
    val statementStubs                                      = stubs.collect { case value: ScImportStmtStub => value }
    val expressionStubs                                     = stubs.collect { case value: ScImportExprStub => value }
    val selectorStubs                                       = stubs.collect { case value: ScImportSelectorStub => value }
    val selectorSetStubs                                    = stubs.collect { case value: ScImportSelectorsStub => value }
    assertEquals(13, statementStubs.size)
    assertEquals(13, expressionStubs.size)
    assertEquals(6, selectorSetStubs.size)
    assertEquals(8, selectorStubs.size)
    assertEquals(s"a.b.$plainName", expressionStubs.head.referenceText.get)
    assertFalse(expressionStubs.head.hasWildcardSelector)
    assertTrue(expressionStubs(1).hasWildcardSelector)
    assertTrue(expressionStubs(6).hasGivenSelector)
    assertEquals(2, selectorStubs.count(_.isAliasedImport))
    assertEquals(4, selectorStubs.count(_.isGivenSelector))
    assertEquals(1, selectorStubs.count(_.isWildcardSelector))
    assertEquals(Vector("T", "T"), selectorStubs.flatMap(_.typeText))
    val enumerator                                          = new TestStringEnumerator
    def bytes(write: StubOutputStream => Unit): Array[Byte] =
      val sink   = new ByteArrayOutputStream
      val output = new StubOutputStream(sink, enumerator)
      write(output)
      output.flush()
      sink.toByteArray
    val statementCopy                                       = ScalaElementType.ImportStatement.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.ImportStatement.serialize(statementStubs.head, _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertNotNull(statementCopy)
    val expressionCopy                                      = ScalaElementType.IMPORT_EXPR.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.IMPORT_EXPR.serialize(expressionStubs(7), _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(expressionStubs(7).referenceText, expressionCopy.referenceText)
    assertEquals(expressionStubs(7).hasWildcardSelector, expressionCopy.hasWildcardSelector)
    assertEquals(expressionStubs(7).hasGivenSelector, expressionCopy.hasGivenSelector)
    val selectorSetCopy                                     = ScalaElementType.IMPORT_SELECTORS.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.IMPORT_SELECTORS.serialize(selectorSetStubs.last, _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(selectorSetStubs.last.hasWildcard, selectorSetCopy.hasWildcard)
    val selectorCopy                                        = ScalaElementType.IMPORT_SELECTOR.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(selectorStubs(1), _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(selectorStubs(1).isAliasedImport, selectorCopy.isAliasedImport)
    assertEquals(selectorStubs(1).referenceText, selectorCopy.referenceText)
    assertEquals(selectorStubs(1).importedName, selectorCopy.importedName)
    assertEquals(selectorStubs(1).aliasName, selectorCopy.aliasName)
    val indexedSelectors                                    = Vector.newBuilder[String]
    selectorStubs.foreach: stub =>
      ScalaElementType.IMPORT_SELECTOR.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.ALIASED_IMPORT_KEY, _indexKey)
            indexedSelectors += value.toString
      )
    assertEquals(Vector("c", "c", "c"), indexedSelectors.result())

  private def assertLegacyAndHiddenImports(file: com.intellij.psi.PsiFile, text: String): Unit =
    assertEquals(text, file.getText)
    val leaves           = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements       = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions      = statements.flatMap(_.importExprs)
    val selectors        = expressions.flatMap(_.selectors)
    assertEquals(Vector("a.b._", "a.b.{c as _}", "a.b.{c => _}"), expressions.map(_.getText))
    assertTrue(expressions.head.hasWildcardSelector)
    assertEquals(Some("_"), expressions.head.wildcardElement.map(_.getText))
    assertEquals(2, selectors.size)
    assertTrue(selectors.forall(_.isAliasedImport))
    assertTrue(selectors.forall(_.reference.exists(_.getText == "c")))
    assertTrue(selectors.forall(_.aliasName.contains("_")))
    val bindings         = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    val underscoreLeaves = leaves.filter(_.getText == "_")
    assertEquals(3, underscoreLeaves.size)
    underscoreLeaves.foreach: leaf =>
      assertSame(
        bindings.elementTypes(NativePsiElementBindings.ImportLegacyWildcardTokenSurface),
        leaf.getNode.getElementType
      )
    val stubs            = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    val selectorStubs    = stubs.collect { case value: ScImportSelectorStub => value }
    assertEquals(3, stubs.count(_.isInstanceOf[ScImportStmtStub]))
    assertEquals(3, stubs.count(_.isInstanceOf[ScImportExprStub]))
    assertEquals(2, selectorStubs.size)
    assertTrue(selectorStubs.forall(_.isAliasedImport))
    assertTrue(selectorStubs.forall(_.aliasName.contains("_")))

  private def indexedExports(packageName: String): Vector[ScExportStmt] =
    StubIndex
      .getElements(
        ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY,
        packageName,
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScExportStmt]
      )
      .asScala
      .toVector

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.*
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.{MetallurgyStatus, MetallurgyStatusListener}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.{IndexSink, PsiFileStubImpl, StubIndex, StubIndexKey, StubInputStream, StubOutputStream}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.{IndexingTestUtil, PlatformTestUtil, ServiceContainerUtil}
import com.intellij.util.io.AbstractStringEnumerator
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParameterizedTypeElement, ScSimpleTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.lang.psi.stubs.{
  ScImportExprStub,
  ScImportSelectorStub,
  ScImportSelectorsStub,
  ScImportStmtStub,
  ScPackagingStub
}
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

final class Scala3PackagePsiProducerTest extends Scala3CompatTestCase:

  def testExactAliasAndAppliedGivenImportsUseNativePhysicalPsi(): Unit =
    val source   =
      "import java as j\nimport a.b.c as _\nimport a.b.given Ordering[Int]\nimport a.b.given F[G[Int]]\nimport a.b.given Either[Int, String]\n"
    val pending  = myFixture.addFileToProject("src/ExactImportCase.scala", source)
    Vector(
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile),
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile).copy().asInstanceOf[com.intellij.psi.PsiFile]
    ).foreach: file =>
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val statements    = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
      val expressions   = statements.flatMap(_.importExprs)
      val selectorSets  = expressions.flatMap(_.selectorSet)
      val selectors     = expressions.flatMap(_.selectors)
      val failure       = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.isEmpty)
      assertEquals(
        Vector(
          "java as j",
          "a.b.c as _",
          "a.b.given Ordering[Int]",
          "a.b.given F[G[Int]]",
          "a.b.given Either[Int, String]"
        ),
        expressions.map(_.getText)
      )
      assertEquals(
        Vector("java as j", "c as _", "given Ordering[Int]", "given F[G[Int]]", "given Either[Int, String]"),
        selectorSets.map(_.getText)
      )
      assertEquals(
        Vector("java as j", "c as _", "given Ordering[Int]", "given F[G[Int]]", "given Either[Int, String]"),
        selectors.map(_.getText)
      )
      assertTrue(selectors.take(2).forall(_.isAliasedImport))
      assertEquals(Vector(Some("j"), Some("_")), selectors.take(2).map(_.aliasName))
      val parameterized = PsiTreeUtil.findChildOfType(file, classOf[ScParameterizedTypeElement])
      assertNotNull(parameterized)
      assertEquals("Ordering[Int]", parameterized.getText)
      assertEquals("Ordering", parameterized.typeElement.getText)
      assertEquals("[Int]", parameterized.typeArgList.getText)
      assertEquals(Vector("Int"), parameterized.typeArgList.typeArgs.map(_.getText).toVector)
      assertSame(parameterized, parameterized.typeElement.getParent)
      assertSame(parameterized, parameterized.typeArgList.getParent)
      val bindings      = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
      val brackets      = PsiTreeUtil
        .collectElements(file, element => element.getFirstChild == null && Set("[", "]")(element.getText))
        .toVector
      assertEquals(8, brackets.size)
      brackets.foreach: bracket =>
        val surface =
          if bracket.getText == "[" then NativePsiElementBindings.TypeArgumentLeftTokenSurface
          else NativePsiElementBindings.TypeArgumentRightTokenSurface
        assertEquals(bindings.elementTypes(surface), bracket.getNode.getElementType)
        assertEquals(1, bracket.getTextRange.getLength)
      val stubs         = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
      assertEquals(5, stubs.count(_.isInstanceOf[ScImportStmtStub]))
      assertEquals(5, stubs.count(_.isInstanceOf[ScImportExprStub]))
      assertEquals(
        Vector("Ordering[Int]", "F[G[Int]]", "Either[Int, String]"),
        stubs.collect { case stub: ScImportSelectorStub => stub }.flatMap(_.typeText)
      )
    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("Ordering"), source.indexOf("Ordering") + 8, "Comparator")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals("Comparator[Int]", PsiTreeUtil.findChildOfType(reparsed, classOf[ScParameterizedTypeElement]).getText)

    val triviaSource = "import a.b /* before */ . /* after */ {c}\nimport a.b /* before */ . /* after */ given T\n"
    val triviaFile   = myFixture.addFileToProject("src/TriviaImportCase.scala", triviaSource)
    val triviaPsi    = PsiManager.getInstance(getProject).findFile(triviaFile.getVirtualFile)
    assertEquals(triviaSource, triviaPsi.getText)
    assertEquals(
      triviaSource,
      PsiTreeUtil.collectElements(triviaPsi, _.getFirstChild == null).toVector.map(_.getText).mkString
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(triviaPsi, classOf[PsiErrorElement]).isEmpty)
    assertEquals(
      Vector("a.b /* before */ . /* after */ {c}", "a.b /* before */ . /* after */ given T"),
      PsiTreeUtil
        .findChildrenOfType(triviaPsi, classOf[ScImportStmt])
        .asScala
        .toVector
        .flatMap(_.importExprs)
        .map(_.getText)
    )

  def testSyntaxCapabilityFailureLifecycleUsesExistingStatusTopic(): Unit =
    val pending        = myFixture.addFileToProject("src/CapabilityStatusCase.scala", "import a.b\n")
    val statuses       = collection.mutable.ArrayBuffer.empty[MetallurgyStatus]
    val connection     = getProject.getMessageBus.connect(getTestRootDisposable)
    connection.subscribe(
      MetallurgyStatus.Topic,
      new MetallurgyStatusListener:
        override def statusChanged(status: MetallurgyStatus): Unit = statuses += status
    )
    val failure        = Scala3SyntaxCapabilityFailure(
      ParserSyntaxSnapshot.digest("import a.b\n"),
      Scala3SyntaxCapabilityStage.Planner,
      "unsupported closed output forest",
      Scala3ParserPreparationLifecycle.get(getProject).stateFor(getModule).currentEpoch,
      None,
      Scala3SyntaxCapabilityRequirement.GrammarRole(None)
    )
    val service        = Scala3SyntaxCapabilityService.get(getProject)
    service.publish(pending.getVirtualFile, failure)
    val afterFirst     = statuses.size
    service.publish(pending.getVirtualFile, failure)
    assertEquals("an identical capability failure must not be published twice", afterFirst, statuses.size)
    val second         = myFixture.addFileToProject("src/SecondCapabilityStatusCase.scala", "import c.d\n")
    service.publish(
      second.getVirtualFile,
      failure.copy(
        sourceDigest = ParserSyntaxSnapshot.digest("import c.d\n"),
        detail = "second unsupported forest",
        requirement = Scala3SyntaxCapabilityRequirement.OutputRole(Some(PsiOutputRoleId.IntegerLiteral.value))
      )
    )
    assertEquals(
      Vector(pending.getVirtualFile, second.getVirtualFile),
      service.currentFailures.flatMap(_.scope.file)
    )
    val published      = statuses.last.asInstanceOf[MetallurgyStatus.SyntaxCapability].report
    assertEquals(Some(second.getVirtualFile), published.scope.file)
    assertEquals(getModule.getName, published.scope.moduleName)
    assertEquals(Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi, published.scope.operation)
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(Some(PsiOutputRoleId.IntegerLiteral.value)),
      published.requirement
    )
    assertEquals(Scala3SyntaxCapabilityState.Unavailable, published.state)
    assertEquals(Scala3SyntaxCapabilityEvidenceState.Recorded, published.evidence.state)
    assertEquals(Scala3SyntaxCapabilityStage.Planner, published.evidence.stage)
    assertEquals("second unsupported forest", published.evidence.detail)
    assertEquals(Scala3SyntaxCapabilityRemediationState.ImplementationRequired, published.remediation)
    assertEquals(Some(ParserSyntaxSnapshot.digest("import c.d\n")), published.sourceDigest)
    assertEquals(failure.preparationEpoch, published.preparationEpoch)
    assertEquals(Scala3SyntaxCapabilityService.RetainedOperations, published.retainedOperations)
    assertTrue(published.compilerCoordinate.nonEmpty)
    assertTrue(published.hostIdentity.ideBuild.nonEmpty)
    assertEquals("org.intellij.scala", published.hostIdentity.scalaPluginId)
    assertTrue(published.hostIdentity.scalaPluginVersion.nonEmpty)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = pending.getVirtualFile.rename(this, "RenamedCapabilityStatusCase.scala")
    )
    assertEquals("RenamedCapabilityStatusCase.scala", service.currentFailures.head.scope.file.get.getName)
    service.discard(Vector(pending.getVirtualFile))
    assertTrue(statuses.last.isInstanceOf[MetallurgyStatus.SyntaxCapability])
    assertEquals(Vector(second.getVirtualFile), service.currentFailures.flatMap(_.scope.file))
    val secondDocument = FileDocumentManager.getInstance.getDocument(second.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = secondDocument.setText("import changed.d\n")
    )
    assertTrue(service.currentFailures.isEmpty)

  def testImportStubSchemaInvalidatesEarlierPersistentData(): Unit =
    assertEquals(
      Math.addExact(org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition.FileNodeType.getStubVersion, 2),
      Scala3DotcParserDefinition.FileNodeType.getStubVersion
    )

  private def indexedPackages(fqn: String): Vector[ScPackaging] =
    StubIndex
      .getElements(
        ScalaIndexKeys.PACKAGE_FQN_KEY,
        fqn,
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScPackaging]
      )
      .asScala
      .toVector

  private def awaitReady(lifecycle: Scala3ParserPreparationLifecycle, label: String): Unit =
    PlatformTestUtil.waitWithEventsDispatching(
      label,
      () => lifecycle.stateFor(getModule).isInstanceOf[ParserPreparationState.Ready],
      10000
    )

  private def assertRecursiveStablePathsPreserveNativePsiPersistenceAndEditIdentity(): Unit =
    val packageSource   = "package alpha.beta.gamma.delta\n"
    val packagePending  = myFixture.addFileToProject("src/RecursivePackageCase.scala", packageSource)
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
    val namedPending = myFixture.addFileToProject("src/NamedRecursivePackageCase.scala", namedSource)
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
    val importPending  = myFixture.addFileToProject("src/RecursiveImportCase.scala", importSource)
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
    val adjacentPending = myFixture.addFileToProject("src/AdjacentRecursivePackageCase.scala", adjacentSource)
    val adjacentFile    = PsiManager.getInstance(getProject).findFile(adjacentPending.getVirtualFile)
    assertEquals(adjacentSource, adjacentFile.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[ScPackaging]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[ScTypeDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[PsiErrorElement]).isEmpty)
    val failure         = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(adjacentPending.getVirtualFile, ParserSyntaxSnapshot.digest(adjacentSource))
    assertTrue(failure.toString, failure.nonEmpty)
    assertEquals(Scala3SyntaxCapabilityStage.AggregateInventory, failure.get.stage)
    assertTrue(adjacentFile.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)

  def testUnsupportedCompilerValidProductionFailsClosedWithCapabilityReason(): Unit =
    val source      = "class Unsupported\n"
    val pending     = myFixture.addFileToProject("src/UnsupportedCase.scala", source)
    val file        = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue("unsupported syntax must publish a file-scoped capability failure", failure.nonEmpty)
    assertEquals(ParserSyntaxSnapshot.digest(source), failure.get.sourceDigest)
    assertEquals(Scala3SyntaxCapabilityStage.AggregateInventory, failure.get.stage)
    assertTrue(failure.get.detail.nonEmpty)
    assertTrue(failure.get.compilerIdentity.nonEmpty)
    val report      = Scala3SyntaxCapabilityService
      .get(getProject)
      .currentFailures
      .find(_.scope.file.contains(pending.getVirtualFile))
      .get
    assertEquals(Scala3SyntaxCapabilityState.Unavailable, report.state)
    assertEquals(Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi, report.scope.operation)
    assertEquals(Scala3SyntaxCapabilityRequirement.GrammarRole(None), report.requirement)
    assertEquals(Some(ParserSyntaxSnapshot.digest(source)), report.sourceDigest)
    assertEquals(failure.get.preparationEpoch, report.preparationEpoch)
    assertEquals(failure.get.compilerIdentity, report.compilerIdentity)
    assertEquals(failure.get.compilerIdentity.map(_.coordinate), report.compilerCoordinate)
    assertEquals(Scala3SyntaxCapabilityEvidenceState.Recorded, report.evidence.state)
    assertEquals(Scala3SyntaxCapabilityStage.AggregateInventory, report.evidence.stage)
    assertEquals(Scala3SyntaxCapabilityRemediationState.ImplementationRequired, report.remediation)
    assertEquals(Scala3SyntaxCapabilityService.RetainedOperations, report.retainedOperations)
    assertTrue(report.hostIdentity.ideBuild.nonEmpty)
    assertTrue(report.hostIdentity.scalaPluginVersion.nonEmpty)
    val _           = myFixture.openFileInEditor(pending.getVirtualFile)
    val errors      = myFixture.doHighlighting().asScala.filter(_.getSeverity == HighlightSeverity.ERROR)
    assertTrue(s"capability findings must not become Scala errors: $errors", errors.isEmpty)
    val fileStubs   = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.toVector
    assertTrue("fail-closed PSI must publish no declaration stubs", fileStubs.drop(1).isEmpty)
    val replacement = failure.get.copy(detail = "new current capability evidence")
    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, replacement)
    Scala3SyntaxCapabilityService
      .get(getProject)
      .resolve(
        pending.getVirtualFile,
        failure.get,
        failure.get.compilerIdentity.get
      )
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
        .contains(replacement)
    )
    Scala3SyntaxCapabilityService
      .get(getProject)
      .resolve(pending.getVirtualFile, replacement, replacement.compilerIdentity.get)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, replacement.sourceDigest)
        .isEmpty
    )
    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, replacement)
    val copy        = file.copy().asInstanceOf[com.intellij.psi.PsiFile]
    assertEquals(source, copy.getText)
    assertEquals(
      Vector(pending.getVirtualFile),
      Scala3SyntaxCapabilityService.get(getProject).currentFailures.flatMap(_.scope.file)
    )
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest("class Changed\n"))
        .isEmpty
    )

    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText("import a.b.c\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertNotNull(PsiTreeUtil.findChildOfType(reparsed, classOf[ScImportStmt]))
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest("import a.b.c\n"))
        .isEmpty
    )

    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, failure.get)
    Scala3ParserPreparationLifecycle.get(getProject).deactivate(getModule)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
        .isEmpty
    )

    val settings = MetallurgySettings(getProject)
    settings.setEnabled(getModule, enabled = false)
    settings.setGloballyEnabled(enabled = true)
    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, failure.get)
    try
      settings.setGloballyEnabled(enabled = false)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
          .isEmpty
      )
    finally
      settings.setGloballyEnabled(enabled = false)
      settings.setEnabled(getModule, enabled = true)

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
    val pending           = myFixture.addFileToProject("src/ImportCase.scala", source)
    val file              = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val _                 = myFixture.openFileInEditor(pending.getVirtualFile)
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
    val groupedPending = myFixture.addFileToProject("src/PackageGroupedImportsCase.scala", groupedSource)
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
    val legacyPending = myFixture.addFileToProject("src/LegacyAndHiddenImportsCase.scala", legacySource)
    val legacyFile    = PsiManager.getInstance(getProject).findFile(legacyPending.getVirtualFile)
    assertLegacyAndHiddenImports(legacyFile, legacySource)
    assertLegacyAndHiddenImports(legacyFile.copy().asInstanceOf[com.intellij.psi.PsiFile], legacySource)
    assertRecursiveStablePathsPreserveNativePsiPersistenceAndEditIdentity()

  def testReadyPhysicalPackageUsesNativePsiAndReparsesAndStubs(): Unit =
    val source     = "package example.syntax\n"
    val installed  = Scala3ParserPreparationLifecycle.get(getProject)
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
    val _          = lifecycle.prepare(getModule)
    val pending    = myFixture.addFileToProject("src/PackageCase.scala", source)
    files = Vector(pending.getVirtualFile)
    preparer.complete(0, new TestParserBridge(Some((bridge, request) => uncoveredSnapshot(request, bridge))))
    awaitReady(lifecycle, "fail-closed package parser activation")
    val failClosed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile).asInstanceOf[PsiFileImpl]
    assertEquals(source, failClosed.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(failClosed, classOf[ScPackaging]).isEmpty)
    assertTrue(failClosed.calcStubTree.getPlainList.asScala.drop(1).isEmpty)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertTrue(indexedPackages("example.syntax").isEmpty)

    val _ = lifecycle.prepare(getModule)
    preparer.complete(1, new TestParserBridge(Some((bridge, request) => packageSnapshot(request, bridge))))
    awaitReady(lifecycle, "covered package parser reactivation")
    assertEquals(3, activation.batchCount)
    assertFalse(failClosed.isValid)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertPlan(lifecycle.parserFor(getModule).get, pending.getVirtualFile.getUrl, source)

    val file = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(file, source, "syntax")
    assertEquals(
      Vector(pending.getVirtualFile),
      indexedPackages("example.syntax").map(_.getContainingFile.getVirtualFile).distinct
    )
    val copy = file.copy().asInstanceOf[com.intellij.psi.PsiFile]
    assertPackage(copy, source, "syntax")

    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(16, 22, "changed")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(reparsed, "package example.changed\n", "changed")

  private def assertPackage(file: com.intellij.psi.PsiFile, text: String, name: String): Unit =
    assertEquals(text, file.getText)
    val leaves    = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText)
    assertEquals(Vector("package", " ", "example", ".", name, "\n"), leaves)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    assertNotNull(packaging)
    val reference = packaging.reference.get
    val qualifier = reference.qualifier.get
    assertEquals(
      "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.packaging.ScPackagingImpl",
      packaging.getClass.getName
    )
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl", reference.getClass.getName)
    assertEquals(ScalaElementType.PACKAGING, packaging.getNode.getElementType)
    assertEquals(ScalaElementType.REFERENCE, reference.getNode.getElementType)
    assertEquals(ScalaElementType.REFERENCE, qualifier.getNode.getElementType)
    assertEquals("package", packaging.keyword.getText)
    assertEquals(s"example.$name", packaging.packageName)
    assertEquals("", packaging.parentPackageName)
    assertEquals(name, reference.refName)
    assertEquals("example", qualifier.refName)
    assertSame(packaging, reference.getParent)
    assertSame(reference, qualifier.getParent)
    assertSame(file, packaging.getContainingFile)
    Vector(packaging, reference, qualifier).foreach: element =>
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    assertEquals(
      Vector(reference),
      packaging.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    assertEquals(
      Vector(qualifier),
      reference.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    val stubTree  = file.asInstanceOf[PsiFileImpl].calcStubTree
    val stub      = stubTree.getPlainList.asScala.collectFirst { case value: ScPackagingStub => value }.orNull
    assertNotNull(stub)
    assertEquals(s"example.$name", stub.packageName)
    assertEquals("", stub.parentPackageName)
    assertFalse(stub.isExplicit)
    assertEquals(ScalaElementType.PACKAGING, stub.getElementType)
    val indexed   = Vector.newBuilder[String]
    ScalaElementType.PACKAGING.indexStub(
      stub,
      new IndexSink:
        override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
          indexed += value.toString
    )
    assertEquals(Vector(s"example.$name", "example"), indexed.result())

  private def assertPackagePath(
      file: com.intellij.psi.PsiFile,
      text: String,
      segments: Vector[String],
      persistence: Boolean
  ): Unit =
    assertEquals(text, file.getText)
    val leaves    = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    assertNotNull(packaging)
    val reference = packaging.reference.get
    assertStablePath(reference, segments)
    assertEquals(segments.mkString("."), packaging.packageName)
    assertEquals("", packaging.parentPackageName)
    assertEquals(
      Vector(reference),
      packaging.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    assertSame(file, packaging.getContainingFile)
    assertSame(getProject, packaging.getProject)
    assertSame(packaging, packaging.getNode.getPsi)
    assertSame(packaging, packaging.getNavigationElement)
    if persistence then
      val stub    = file
        .asInstanceOf[PsiFileImpl]
        .calcStubTree
        .getPlainList
        .asScala
        .collectFirst { case value: ScPackagingStub => value }
        .orNull
      assertNotNull(stub)
      assertEquals(segments.mkString("."), stub.packageName)
      assertEquals("", stub.parentPackageName)
      assertFalse(stub.isExplicit)
      val indexed = Vector.newBuilder[String]
      ScalaElementType.PACKAGING.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.PACKAGE_FQN_KEY, _indexKey)
            indexed += value.toString
      )
      assertEquals(
        segments.indices.reverse
          .map(index => segments.take(index + 1).map(_.stripPrefix("`").stripSuffix("`")).mkString("."))
          .toVector,
        indexed.result()
      )

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

  private def assertStablePath(reference: ScStableCodeReference, segments: Vector[String]): Unit =
    var current = Option(reference)
    segments.indices.reverse.foreach: index =>
      val value        = current.getOrElse(throw new AssertionError(s"missing stable path segment ${segments(index)}"))
      val expectedText = segments.take(index + 1).mkString(".")
      assertEquals(expectedText, value.getText)
      assertEquals(segments(index), value.refName)
      assertEquals(segments(index), value.nameId.getText)
      assertEquals(ScalaElementType.REFERENCE, value.getNode.getElementType)
      assertEquals(value.getTextRange.getStartOffset + expectedText.length, value.getTextRange.getEndOffset)
      assertEquals(
        value.getTextRange.getEndOffset - segments(index).length,
        value.nameId.getTextRange.getStartOffset
      )
      assertSame(getProject, value.getProject)
      assertSame(value, value.getNode.getPsi)
      assertSame(value, value.getNavigationElement)
      val qualifier    = value.qualifier
      assertEquals(
        qualifier.toVector,
        value.getChildren.toVector.collect { case child: ScStableCodeReference => child }
      )
      qualifier.foreach(child => assertSame(value, child.getParent))
      current = qualifier
    assertTrue(current.isEmpty)

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

  private final class TestStringEnumerator extends AbstractStringEnumerator:
    private val values = collection.mutable.ArrayBuffer.empty[String]

    override def enumerate(value: String): Int =
      val index = values.indexOf(value)
      if index >= 0 then index + 1
      else
        values += value
        values.size

    override def valueOf(id: Int): String = values(id - 1)
    override def isDirty: Boolean         = false
    override def force(): Unit            = ()
    override def markCorrupted(): Unit    = ()
    override def close(): Unit            = ()

  private def assertPlan(prepared: PreparedScala3Parser, uri: String, source: String): Unit =
    val request   = Scala3ParserRequest(ParserSourceUri.from(uri).toOption.get, source, prepared.compilerOptions)
    val snapshot  = prepared.bridge.parse(request).fold(error => throw new AssertionError(error.toString), identity)
    val evidence  = ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val runtime   = CompilerRuntimeInventory
      .from(snapshot)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val aggregate = AggregatedCompilerProductionInventory
      .aggregate(Vector(runtime))
      .fold(error => throw new AssertionError(error.toString), identity)
    val catalog   = PreparedProductionCatalog
      .prepareRuntimeSubset(prepared.catalog, runtime, aggregate, prepared.surfaces)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val plan      = WholeFileProductionPlanner
      .plan(snapshot, evidence, catalog)
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(3, plan.composites.size)

  private def uncoveredSnapshot(
      request: Scala3ParserRequest,
      bridge: TestParserBridge
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    val source = request.sourceText
    Right(
      ParserSyntaxSnapshot(
        request.sourceUri,
        source,
        ParserSyntaxSnapshot.digest(source),
        source.length,
        request.compilerOptions,
        1,
        Vector(
          ParserSyntaxNode(
            1,
            "Uncovered",
            Vector.empty,
            ParserNodePosition.Positioned(
              PcSourceRange(0, source.length),
              0,
              ParserPositionProvenance.SourceDerived
            ),
            Vector.empty
          )
        ),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        bridge.capabilities,
        bridge.identity
      )
    )

  private def packageSnapshot(
      request: Scala3ParserRequest,
      bridge: TestParserBridge
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    val source = request.sourceText
    if !source.matches("package [^.\\n]+\\.[^.\\n]+\\n") then Left(Scala3ParserError.Closed)
    else
      val dot      = source.indexOf('.')
      val end      = source.length - 1
      val position = (from: Int, point: Int, to: Int) =>
        ParserNodePosition.Positioned(
          PcSourceRange(from, to),
          point,
          ParserPositionProvenance.SourceDerived
        )
      val nodes    = Vector(
        ParserSyntaxNode(
          1,
          "PackageDef",
          Vector(
            ParserSyntaxField("pid", ParserFieldValue.Node(2), Some(ParserDeclaredShape.Node)),
            ParserSyntaxField(
              "stats",
              ParserFieldValue.Repeated(Vector.empty),
              Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
            )
          ),
          position(0, dot + 1, end),
          Vector.empty
        ),
        ParserSyntaxNode(
          2,
          "Select",
          Vector(
            ParserSyntaxField("qualifier", ParserFieldValue.Node(3), Some(ParserDeclaredShape.Node)),
            ParserSyntaxField(
              "name",
              ParserFieldValue.Name(source.substring(dot + 1, end)),
              Some(ParserDeclaredShape.Name)
            )
          ),
          position(8, dot + 1, end),
          Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("pid"))))
        ),
        ParserSyntaxNode(
          3,
          "Ident",
          Vector(
            ParserSyntaxField(
              "name",
              ParserFieldValue.Name(source.substring(8, dot)),
              Some(ParserDeclaredShape.Name)
            )
          ),
          position(8, 8, dot),
          Vector(ParserNodeOccurrence(2, Vector(ParserFieldPathSegment.NamedField("qualifier"))))
        )
      )
      Right(
        ParserSyntaxSnapshot(
          request.sourceUri,
          source,
          ParserSyntaxSnapshot.digest(source),
          source.length,
          request.compilerOptions,
          1,
          nodes,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          bridge.capabilities,
          bridge.identity
        )
      )

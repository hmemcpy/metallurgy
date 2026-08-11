package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.hmemcpy.metallurgy.status.MetallurgyStatusListener
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScExportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}
import scala.jdk.CollectionConverters.*

private[psiproducer] trait Scala3PackagePsiCapabilityTests extends Scala3PackagePsiProducerTestSupport:
  def testSyntaxCapabilityFailureLifecycleUsesExistingStatusTopic(): Unit =
    val pending        = codeInsightFixture.addFileToProject("src/CapabilityStatusCase.scala", "import a.b\n")
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
      Scala3ParserPreparationLifecycle.get(getProject).stateFor(super.getModule).currentEpoch,
      None,
      Scala3SyntaxCapabilityRequirement.GrammarRole(None)
    )
    val service        = Scala3SyntaxCapabilityService.get(getProject)
    service.publish(pending.getVirtualFile, failure)
    val afterFirst     = statuses.size
    service.publish(pending.getVirtualFile, failure)
    assertEquals("an identical capability failure must not be published twice", afterFirst, statuses.size)
    val second         = codeInsightFixture.addFileToProject("src/SecondCapabilityStatusCase.scala", "import c.d\n")
    service.publish(
      second.getVirtualFile,
      failure.copy(
        sourceDigest = ParserSyntaxSnapshot.digest("import c.d\n"),
        detail = "second unsupported forest",
        requirement = Scala3SyntaxCapabilityRequirement.OutputRole(Some(PsiOutputRoleId.IntegerExpression.value))
      )
    )
    assertEquals(
      Vector(pending.getVirtualFile, second.getVirtualFile),
      service.currentFailures.flatMap(_.scope.file)
    )
    val published      = statuses.last.asInstanceOf[MetallurgyStatus.SyntaxCapability].report
    assertEquals(Some(second.getVirtualFile), published.scope.file)
    assertEquals(super.getModule.getName, published.scope.moduleName)
    assertEquals(Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi, published.scope.operation)
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(Some(PsiOutputRoleId.IntegerExpression.value)),
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

  def testUnsupportedCompilerValidProductionFailsClosedWithCapabilityReason(): Unit =
    val source      = "class Parent(value: Int)\nclass Unsupported extends Parent(1)\n"
    val pending     = codeInsightFixture.addFileToProject("src/UnsupportedCase.scala", source)
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
    assertEquals(Scala3SyntaxCapabilityStage.Catalog, failure.get.stage)
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
    assertEquals(Scala3SyntaxCapabilityStage.Catalog, report.evidence.stage)
    assertEquals(Scala3SyntaxCapabilityRemediationState.ImplementationRequired, report.remediation)
    assertEquals(Scala3SyntaxCapabilityService.RetainedOperations, report.retainedOperations)
    assertTrue(report.hostIdentity.ideBuild.nonEmpty)
    assertTrue(report.hostIdentity.scalaPluginVersion.nonEmpty)
    val _           = codeInsightFixture.openFileInEditor(pending.getVirtualFile)
    val errors      = codeInsightFixture.doHighlighting().asScala.filter(_.getSeverity == HighlightSeverity.ERROR)
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

    assertUnsupportedExportsFailClosedWithoutPartialPersistence()
    assertUnsupportedGivenTypesFailClosedWithoutPartialPersistence()
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
    Scala3ParserPreparationLifecycle.get(getProject).deactivate(super.getModule)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
        .isEmpty
    )

    val settings = MetallurgySettings(getProject)
    settings.setEnabled(super.getModule, enabled = false)
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
      settings.setEnabled(super.getModule, enabled = true)

  private def assertUnsupportedExportsFailClosedWithoutPartialPersistence(): Unit =
    val sources = Vector(
      "object Owner:\n  export scala.Predef.identity\n",
      "extension (value: Int)\n  export scala.Predef.identity\n",
      "export scala.Predef.{given (A, B)}\n"
    )
    sources.zipWithIndex.foreach: (source, index) =>
      val pending = codeInsightFixture.addFileToProject(s"src/UnsupportedExportCase$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      assertTrue(file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.nonEmpty)

  private def assertUnsupportedGivenTypesFailClosedWithoutPartialPersistence(): Unit =
    val types   = Vector(
      "?",
      "(A, B)",
      "A => B",
      "A match { case _ => B }",
      "A { type X }",
      "A @ann",
      "(x: A) => x.B",
      "new A"
    )
    val sources = types.flatMap(bound => Vector(s"import a.b.given $bound\n", s"export a.b.{given $bound}\n"))
    sources.zipWithIndex.foreach: (source, index) =>
      val pending      = codeInsightFixture.addFileToProject(s"src/UnsupportedGivenTypeCase$index.scala", source)
      val file         = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).isEmpty)
      val parserErrors = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).asScala.toVector
      assertTrue(
        parserErrors.forall(error =>
          error.getTextRange.getStartOffset >= 0 && error.getTextRange.getStartOffset <= source.length &&
            error.getTextRange.getEndOffset <= source.length && error.getErrorDescription.nonEmpty
        )
      )
      assertEquals(
        parserErrors.size,
        parserErrors.map(error => error.getTextRange -> error.getErrorDescription).distinct.size
      )
      assertTrue(file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)
      val failure      = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"$source: $failure", failure.nonEmpty)

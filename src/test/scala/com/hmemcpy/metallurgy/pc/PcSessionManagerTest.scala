package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.compilerbackend.{
  CompilerBackendPublication,
  CompilerBackendRole,
  CompilerBackendState,
  Scala3CompilerBackend
}
import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.feature.diagnostics.{PcDiagnosticSetCache, SnapshotState}
import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.junit.Assert.{assertEquals, assertFalse, assertNotSame, assertNull, assertSame, assertTrue, fail}

import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

final class PcSessionManagerTest extends ScalaLightCodeInsightFixtureTestCase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == testScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(testScalaVersion)

  def testBundledResolverLoadsAWorkingPresentationCompiler(): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-real-pc")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)

    try
      settings.setEnabled(getModule, enabled = true)
      val directory = onPooledThread:
        fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS)
      val names     = Files.list(directory)
      try
        val artifactNames = names.iterator().asScala.map(_.getFileName.toString).toSeq
        assertTrue(artifactNames.exists(_.startsWith("scala3-presentation-compiler_3-3.5.2")))
        assertTrue(artifactNames.exists(_.startsWith("scala3-compiler_3-3.5.2")))
        assertTrue(artifactNames.exists(_.startsWith("mtags-interfaces-")))
      finally names.close()

      val source =
        """object Main:
          |  val values = List(1)
          |  values.""".stripMargin
      val items  = onPooledThread:
        val session = PcSession.create(
          testScalaVersion.minor,
          moduleClasspath,
          ScalacFlagsService.get(getProject).compilerOptions(getModule),
          fetcher
        )
        try
          val outcome = session
            .scheduleRetypecheck(PcSnapshot("file:///Main.scala", 1L, source))
            .get(5, TimeUnit.SECONDS)
          assertEquals(RetypecheckOutcome.Applied, outcome)
          session.complete("file:///Main.scala", source, 1L, source.length)
        finally session.close()

      assertTrue(
        items.map(item => s"${item.lookupName} [${item.label}]").mkString(", "),
        items.exists(item => item.lookupName == "map" || item.label.startsWith("map"))
      )
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  def testRetypecheckIsLatestWins(): Unit = withRealPcSession("metallurgy-latest-wins"): session =>
    val staleSource   =
      """object Main:
        |  transparent inline def port = 8080
        |  val selected = port""".stripMargin
    val currentSource = staleSource.replace("8080", "9090")
    val superseded    = session.scheduleRetypecheck(PcSnapshot("file:///TransparentInline.scala", 1L, staleSource))
    val applied       = session.scheduleRetypecheck(PcSnapshot("file:///TransparentInline.scala", 2L, currentSource))

    assertEquals(RetypecheckOutcome.Superseded, superseded.get(5, TimeUnit.SECONDS))
    assertEquals(RetypecheckOutcome.Applied, applied.get(5, TimeUnit.SECONDS))
    val rendered = TypeRenderer.render(
      session,
      PcSnapshot("file:///TransparentInline.scala", 2L, currentSource),
      currentSource.lastIndexOf("port")
    )
    assertTrue(rendered.toString, rendered.exists(_.contains("9090")))

  def testPublishedInlineTypeDriverIsReusedByQueries(): Unit =
    withRealPcSession("metallurgy-inline-driver"): session =>
      val source   =
        """object Main:
          |  transparent inline def port = 8080
          |  val selected = port""".stripMargin
      val snapshot = PcSnapshot("file:///TransparentInline.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      val _ = TypeRenderer.render(session, snapshot, source.lastIndexOf("port"))
      val _ = TypeRenderer.render(session, snapshot, source.lastIndexOf("port") + 1)
      assertEquals(1, session.inlineDriverCreationCount)

  def testTypeAtFallsBackToTheInnermostTypedTree(): Unit =
    withRealPcSession("metallurgy-ordinary-type"): session =>
      val source   = "object Main:\n  val answer = List(42).head\n"
      val snapshot = PcSnapshot("file:///OrdinaryType.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      assertEquals(
        Some("Int"),
        TypeRenderer.render(session, snapshot, source.lastIndexOf("head"))
      )

  def testTypeAtDoesNotSelectAnUnrelatedInlineAncestor(): Unit =
    withRealPcSession("metallurgy-tuple-head-type"): session =>
      val source   = "object Main:\n  val tuple = (1, \"two\", true)\n  val selected = tuple.tail.head\n"
      val snapshot = PcSnapshot("file:///TupleHeadType.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      assertEquals(
        Some("String"),
        TypeRenderer.render(session, snapshot, source.lastIndexOf("head"))
      )

  def testTypeAtResolvesNestedTupleQualifierRange(): Unit =
    withRealPcSession("metallurgy-nested-tuple-qualifier"): session =>
      val source   = "object Main:\n  val tuple = (1, \"two\", true)\n  val selected = tuple.tail.head\n"
      val snapshot = PcSnapshot("file:///NestedTupleQualifier.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)
      val start    = source.lastIndexOf("tuple.tail")
      val end      = start + "tuple.tail".length

      assertEquals(RetypecheckOutcome.Applied, outcome)
      assertEquals(
        Some("(String, Boolean)"),
        TypeRenderer.render(session, snapshot, TextRange.create(start, end))
      )

  def testTypeAtDealiasesAnExplicitSingletonAlias(): Unit =
    withRealPcSession("metallurgy-singleton-alias-type"): session =>
      val source   =
        "import scala.compiletime.ops.int.*\nobject Main:\n  type Four = 2 + 2\n  val result: Four = 4\n  val selected: 4 = result\n"
      val snapshot = PcSnapshot("file:///SingletonAliasType.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      assertEquals(
        Some("(4 : Int)"),
        TypeRenderer.render(session, snapshot, source.lastIndexOf("result"))
      )

  def testTypeAtSelectsTheAppliedResultOfAContextualExtension(): Unit =
    withRealPcSession("metallurgy-contextual-extension-type"): session =>
      val source   =
        "import scala.quoted.*\nobject Main:\n  def read(n: Expr[Int])(using Quotes): Int = n.valueOrError\n"
      val snapshot = PcSnapshot("file:///ContextualExtensionType.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      assertEquals(
        Some("Int"),
        TypeRenderer.render(
          session,
          snapshot,
          TextRange.from(source.lastIndexOf("n.valueOrError"), "n.valueOrError".length)
        )
      )

  def testTypeAtUsesTheResultOfACompilerGeneratedStructuralCast(): Unit =
    withRealPcSession("metallurgy-structural-selection-type"): session =>
      val source   =
        "import scala.reflect.Selectable.reflectiveSelectable\nobject Main:\n  val structural: { val answer: Int } = new { val answer = 42 }\n  val selected = structural.answer\n"
      val snapshot = PcSnapshot("file:///StructuralSelectionType.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      assertEquals(
        Some("Int"),
        TypeRenderer.render(
          session,
          snapshot,
          TextRange.from(source.lastIndexOf("structural.answer"), "structural.answer".length)
        )
      )

  def testStructuralCompletionIncludesBacktickedMembers(): Unit =
    withRealPcSession("metallurgy-structural-completion"): session =>
      val source   =
        """import scala.reflect.Selectable.reflectiveSelectable
          |object Main:
          |  val structural: { val `/pet`: Int } = new { val `/pet` = 42 }
          |  val selected = structural.`
          |""".stripMargin
      val snapshot = PcSnapshot("file:///StructuralCompletion.scala", 1L, source)
      val driver   = new PcInlineTypeDriver(session.classloader, session.compilerClasspath, session.compilerOptions)

      try
        driver.retypecheck(snapshot)
        val completions = driver.structuralCompletions(snapshot, source.length - 1)
        assertTrue(
          completions.toString,
          completions.exists(item => item.lookupName == "`/pet`" && item.detail.contains("Int"))
        )
      finally driver.close()

  def testCompletionNormalizesNonFileUris(): Unit =
    withRealPcSession("metallurgy-non-file-completion"): session =>
      val source   = "object Main:\n  val values = List(1)\n  values."
      val snapshot = PcSnapshot("temp:///src/Completion.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      val items = session.complete(snapshot.fileUri, source, snapshot.documentVersion, source.length)
      assertTrue(items.toString, items.exists(item => item.lookupName == "map" || item.label.startsWith("map")))

  def testDiagnosticsAreBoundToThePublishedDocumentVersion(): Unit =
    withRealPcSession("metallurgy-pc-diagnostics"): session =>
      val source   = "object Main:\n  val value: String = 1\n"
      val snapshot = PcSnapshot("temp:///src/Diagnostics.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(5, TimeUnit.SECONDS)

      assertEquals(RetypecheckOutcome.Applied, outcome)
      val diagnostics = session.diagnostics(snapshot)
      assertTrue(diagnostics.toString, diagnostics.exists(_.exists(_.isError)))
      assertTrue(session.diagnostics(PcSnapshot(snapshot.fileUri, 2L, source)).isEmpty)

  def testTypedTreeSnapshotRendersDefinitionRolesExactly(): Unit =
    withRealPcSession("metallurgy-typed-tree-definitions"): session =>
      val source    =
        """object Main:
          |  def function(parameter: Int): String = parameter.toString
          |  val inferred = function(42)
          |""".stripMargin
      val extracted = extractTypedTreeSnapshot(session, "TypedTreeDefinitions", source)

      assertSnapshotEntry(
        source,
        extracted,
        "def function(parameter: Int): String = parameter.toString",
        PcTypedTreeRole.Function,
        "(Main.function : (parameter: Int): String)"
      )
      assertSnapshotEntry(
        source,
        extracted,
        "def function(parameter: Int): String = parameter.toString",
        PcTypedTreeRole.FunctionResult,
        "String"
      )
      assertSnapshotEntry(source, extracted, "parameter: Int", PcTypedTreeRole.Parameter, "Int")
      assertSnapshotEntry(source, extracted, "val inferred = function(42)", PcTypedTreeRole.Inferred, "String")
      assertSnapshotEntry(source, extracted, "String", PcTypedTreeRole.Declared, "String")
      val inferredStart = source.indexOf("function(42)")
      val inferredRange = PcSourceRange(inferredStart, inferredStart + "function(42)".length)
      assertFalse(
        extracted.entries.exists(entry => entry.range == inferredRange && entry.role == PcTypedTreeRole.Declared)
      )

  def testTypedTreeSnapshotRanksInlineApplyAndTypeApplyOverlap(): Unit =
    withRealPcSession("metallurgy-typed-tree-overlap"): session =>
      val source    =
        """object Main:
          |  transparent inline def identity[A](inline value: A): A = value
          |  val inferred = identity[Int](42)
          |""".stripMargin
      val extracted = extractTypedTreeSnapshot(session, "TypedTreeOverlap", source)

      assertSnapshotEntry(source, extracted, "identity[Int](42)", PcTypedTreeRole.ExpressionExact, "(42 : Int)")
      assertSnapshotEntry(source, extracted, "identity[Int](42)", PcTypedTreeRole.ExpressionWidened, "Int")
      val retainedKeys = extracted.entries.map(entry => (entry.range, entry.role, entry.symbol.map(_.id)))
      assertEquals(retainedKeys.distinct.size, retainedKeys.size)
      assertTrue(extracted.metrics.deduplicatedCandidateCount > 0)
      assertTrue(extracted.metrics.compilerWrapperOverlapCount > 0)

  def testTypedTreeSnapshotMapsEachDestructuredBinding(): Unit =
    withRealPcSession("metallurgy-typed-tree-patterns"): session =>
      val source    = "object Main:\n  val pair = (1, \"two\")\n  val (number, text) = pair\n"
      val extracted = extractTypedTreeSnapshot(session, "TypedTreePatterns", source)

      assertSnapshotEntry(source, extracted, "number", PcTypedTreeRole.Pattern, "Int")
      assertSnapshotEntry(source, extracted, "text", PcTypedTreeRole.Pattern, "String")
      assertSnapshotEntry(source, extracted, "number", PcTypedTreeRole.PatternExpected, "Int")
      assertSnapshotEntry(source, extracted, "text", PcTypedTreeRole.PatternExpected, "String")
      val patterns = extracted.entries.filter(_.role == PcTypedTreeRole.Pattern)
      assertTrue(patterns.nonEmpty)
      assertTrue(patterns.forall(_.symbol.nonEmpty))

  def testTypedTreeSnapshotRetainsRefinementTypes(): Unit =
    withRealPcSession("metallurgy-typed-tree-refinement"): session =>
      val source    = "object Main:\n  val refined: { val member: Int } = new { val member = 42 }\n"
      val extracted = extractTypedTreeSnapshot(session, "TypedTreeRefinement", source)

      assertSnapshotEntry(
        source,
        extracted,
        "val refined: { val member: Int } = new { val member = 42 }",
        PcTypedTreeRole.Inferred,
        "Object{val member: Int}"
      )

  def testTypedTreeSnapshotExcludesErrorOnlyTrees(): Unit =
    withRealPcSession("metallurgy-typed-tree-errors"): session =>
      val source    = "object Main:\n  val erroneous: Boolean = missing\n"
      val extracted = extractTypedTreeSnapshot(session, "TypedTreeErrors", source)

      assertSnapshotEntry(
        source,
        extracted,
        "val erroneous: Boolean = missing",
        PcTypedTreeRole.Inferred,
        "Boolean"
      )
      assertFalse(extracted.entries.exists(_.renderedType.contains("<error")))

  def testTypedTreeSnapshotContainsBoundarySafeSymbolMetadata(): Unit =
    withRealPcSession("metallurgy-typed-tree-metadata"): session =>
      val source    = "object Main:\n  def answer(value: Int): Int = value\n"
      val extracted = extractTypedTreeSnapshot(session, "TypedTreeMetadata", source)
      val symbols   = extracted.entries.flatMap(_.symbol)

      assertTrue(symbols.nonEmpty)
      assertTrue(symbols.exists(_.ownerId.nonEmpty))
      assertTrue(symbols.exists(_.navigation.exists(_.fileUri == "file:///TypedTreeMetadata.scala")))
      assertTrue(
        extracted.entries.forall(entry => entry.range.startOffset >= 0 && entry.range.endOffset <= source.length)
      )
      assertTrue(extracted.entries.forall(_.renderedType.nonEmpty))
      assertTrue(extracted.metrics.visitedTreeCount >= extracted.metrics.positionedTreeCount)
      assertEquals(extracted.entries.size, extracted.metrics.renderedTypeCount)
      assertEquals(extracted.metrics.retainedEntryCount, extracted.metrics.renderedTypeCount)

  def testTypedTreeSnapshotRejectsSupersededDocumentVersions(): Unit =
    withRealPcSession("metallurgy-stale-typed-tree-snapshot"): session =>
      val stale              = PcSnapshot("file:///StaleTypedTree.scala", 1L, "object Main:\n  val value = 1\n")
      val current            = PcSnapshot("file:///StaleTypedTree.scala", 2L, "object Main:\n  val value = \"current\"\n")
      val _                  = session.scheduleRetypecheck(stale).get(5, TimeUnit.SECONDS)
      val currentRetypecheck = session.scheduleRetypecheck(current)

      assertTrue(session.typedTreeSnapshot(stale).isEmpty)
      assertEquals(RetypecheckOutcome.Applied, currentRetypecheck.get(5, TimeUnit.SECONDS))
      assertTrue(session.typedTreeSnapshot(current).exists(_.documentVersion == 2L))

  def testTypedTreeExtractionStopsWhenItsGenerationIsSuperseded(): Unit =
    withRealPcSession("metallurgy-cancel-typed-tree-snapshot"): session =>
      val source   =
        (1 to 100).map(index => s"  val value$index = List($index).head").mkString("object Main:\n", "\n", "\n")
      val snapshot = PcSnapshot("file:///CanceledTypedTree.scala", 1L, source)
      val driver   = new PcInlineTypeDriver(session.classloader, session.compilerClasspath, session.compilerOptions)
      val checks   = new AtomicInteger(0)
      val currency = () =>
        if checks.incrementAndGet() < 4 then PcSnapshotCurrency.Current
        else PcSnapshotCurrency.Superseded

      try
        driver.retypecheck(snapshot)
        assertEquals(PcTypedTreeExtraction.Superseded, driver.typedTreeSnapshot(snapshot, currency))
        assertEquals(4, checks.get())
      finally driver.close()

  def testTypedTreeSnapshotRecordsCorpusMetrics(): Unit =
    withRealPcSession("metallurgy-typed-tree-corpora"): session =>
      val ordinary  = "object Ordinary:\n  val answer = List(42).head\n"
      val large     =
        (1 to 500).map(index => s"  val value$index = List($index).head").mkString("object Large:\n", "\n", "\n")
      val typeLevel =
        """import scala.compiletime.ops.int.*
          |object TypeLevel:
          |  type Answer = 40 + 2
          |  transparent inline def answer: Int = 42
          |  val result: Answer = answer
          |""".stripMargin
      val corpora   = Seq("Ordinary" -> ordinary, "Large" -> large, "TypeLevel" -> typeLevel)

      val measured = corpora.zipWithIndex.map: (corpus, index) =>
        val (name, source) = corpus
        val snapshot       = PcSnapshot(s"file:///$name.scala", index.toLong + 1L, source)
        val outcome        = session.scheduleRetypecheck(snapshot).get(10, TimeUnit.SECONDS)
        assertEquals(s"$name retypecheck", RetypecheckOutcome.Applied, outcome)
        val metrics        = session
          .typedTreeSnapshot(snapshot)
          .getOrElse(throw new AssertionError(s"$name typed-tree snapshot was not available"))
          .metrics
        println(
          s"[typed-tree] $name: ${metrics.extractionDuration.toMillis}ms total, " +
            s"${metrics.traversalDuration.toMillis}ms traverse/dedupe, " +
            s"${metrics.renderingDuration.toMillis}ms render, " +
            s"${metrics.visitedTreeCount} nodes, ${metrics.candidateCount} candidates, " +
            s"${metrics.deduplicatedCandidateCount} deduplicated"
        )
        metrics

      assertTrue(measured.forall(_.extractionDuration.length >= 0L))
      assertTrue(measured.forall(_.traversalDuration.length >= 0L))
      assertTrue(measured.forall(_.renderingDuration.length >= 0L))
      assertTrue(measured.forall(_.visitedTreeCount > 0))
      assertTrue(measured.forall(_.renderedTypeCount > 0))
      assertTrue(measured(1).visitedTreeCount > measured.head.visitedTreeCount)

  def testWriterMirrorsRetypecheckOutcomeToCache(): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-writer")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.direct
    )
    val manager            = new PcSessionManager(getProject, fetcher)
    val settings           = MetallurgySettings(getProject)
    val cache              = PcDiagnosticSetCache.get(getProject)

    def assertPublishedState(file: VirtualFile, expectError: Boolean): Unit =
      val prepare: Computable[CompletableFuture[Option[PcSession]]] = () => manager.prepareFile(file)
      val prepared                                                  =
        onPooledThread(ApplicationManager.getApplication.runReadAction(prepare).get(60, TimeUnit.SECONDS))
      assertTrue(s"session was not prepared for $file", prepared.isDefined)
      val readStamp: Computable[java.lang.Long]                     =
        () => FileDocumentManager.getInstance.getDocument(file).getModificationStamp
      val version                                                   = onPooledThread(ApplicationManager.getApplication.runReadAction(readStamp).longValue())
      cache.stateFor(file.getUrl, version) match
        case SnapshotState.CurrentSuccess(_, diagnostics) =>
          val hasError = diagnostics.exists(_.isError)
          if expectError then assertTrue(s"expected an error diagnostic, got: $diagnostics", hasError)
          else assertTrue(s"expected no errors, got: $diagnostics", !hasError)
        case other                                        => fail(s"expected CurrentSuccess for $file, got: $other")

    try
      settings.setEnabled(getModule, enabled = true)
      setCompilerBasedHighlighting(enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS))

      // pc flags this as a type error → the cache publishes CurrentSuccess carrying it.
      val errorFile =
        myFixture.addFileToProject("WriterError.scala", "object WriterError:\n  val value: String = 1\n").getVirtualFile
      assertPublishedState(errorFile, expectError = true)

      // a clean source → empty CurrentSuccess (pc reports "clean").
      val cleanFile =
        myFixture.addFileToProject("WriterClean.scala", "object WriterClean:\n  val value: Int = 1\n").getVirtualFile
      assertPublishedState(cleanFile, expectError = false)
    finally
      manager.dispose()
      settings.setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
      deleteRecursively(temporaryDirectory)

  def testEligibilityOptInReuseAndDiscardLifecycle(): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-session-manager")
    val artifact           = Files.write(temporaryDirectory.resolve("presentation-compiler.jar"), Array[Byte](1))
    val resolver           = FixedPresentationCompilerResolver(artifact)
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      resolver,
      BackgroundRunner.direct
    )
    val manager            = new PcSessionManager(getProject, fetcher)
    val settings           = MetallurgySettings(getProject)

    try
      assertTrue(onPooledThread(manager.sessionFor(getModule)).isEmpty)

      settings.setEnabled(getModule, enabled = true)
      setCompilerBasedHighlighting(enabled = true) // Metallurgy requires CBH
      val first  = manager.sessionForAsync(getModule).get(5, TimeUnit.SECONDS).get
      val second = onPooledThread(manager.sessionFor(getModule)).get
      assertSame(first, second)
      assertTrue(ScalacFlagsService.RequiredFlags.forall(first.compilerOptions.contains))

      onPooledThread(manager.discard(getModule))
      assertTrue(first.isClosed)

      val replacement = onPooledThread(manager.sessionFor(getModule)).get
      assertNotSame(first, replacement)
      assertFalse(replacement.isClosed)

      // without CBH, Metallurgy is a no-op even when enabled.
      setCompilerBasedHighlighting(enabled = false)
      assertTrue(onPooledThread(manager.sessionFor(getModule)).isEmpty)
      setCompilerBasedHighlighting(enabled = true)

      settings.setEnabled(getModule, enabled = false)
      assertTrue(onPooledThread(manager.sessionFor(getModule)).isEmpty)
      assertTrue(replacement.isClosed)
    finally
      manager.dispose()
      settings.setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
      deleteRecursively(temporaryDirectory)

  def testSessionDiscardRetiresCompilerBackendState(): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-session-retirement")
    val artifact           = Files.write(temporaryDirectory.resolve("presentation-compiler.jar"), Array[Byte](1))
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      FixedPresentationCompilerResolver(artifact),
      BackgroundRunner.direct
    )
    val manager            = new PcSessionManager(getProject, fetcher)
    val settings           = MetallurgySettings(getProject)

    try
      settings.setEnabled(getModule, enabled = true)
      setCompilerBasedHighlighting(enabled = true)
      val session    = manager.sessionForAsync(getModule).get(5, TimeUnit.SECONDS).get
      val sourceFile = myFixture.configureByText("SessionRetirement.scala", "val value = List(1).head")
      val expression = PsiTreeUtil
        .findChildrenOfType(sourceFile, classOf[ScExpression])
        .asScala
        .find(_.getText == "List(1).head")
        .get
      val version    = myFixture.getEditor.getDocument.getModificationStamp
      val backend    = Scala3CompilerBackend.get(getProject)
      assertEquals(
        CompilerBackendPublication.Published,
        backend.publish(expression, CompilerBackendRole.ExpressionExact, version, "String")
      )
      assertEquals("_root_.scala.Predef.String", expression.getTypeWithoutImplicits().toOption.get.canonicalText)
      assertEquals("String", BundledPluginBridge.getCompilerType(expression))

      onPooledThread(manager.discard(getModule))
      UIUtil.dispatchAllInvocationEvents()

      assertTrue(session.isClosed)
      assertEquals(
        CompilerBackendState.Unavailable,
        backend.stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
      )
      assertNull(BundledPluginBridge.getCompilerType(expression))
    finally
      manager.dispose()
      settings.setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
      deleteRecursively(temporaryDirectory)

  def testBuildClasspathExposesBestEffortTastyRoots(): Unit =
    // A directory root carrying META-INF/best-effort gets that subdir appended as a classpath root.
    val broken      = Files.createTempDirectory("brokenOut")
    val bestEffort  = Files.createDirectories(broken.resolve("META-INF").resolve("best-effort"))
    val _           = Files.createFile(bestEffort.resolve("Person.betasty"))
    val withBetasty = PcSessionManager.exposeBestEffortTastyRoots(Seq(broken.toFile))
    assertTrue(withBetasty.mkString(", "), withBetasty.exists(_.getPath.endsWith("best-effort")))

    // A clean directory root (no betasty) is left unchanged.
    val clean          = Files.createTempDirectory("cleanOut").toFile
    val withoutBetasty = PcSessionManager.exposeBestEffortTastyRoots(Seq(clean))
    assertEquals(Seq(clean), withoutBetasty)

  private def onPooledThread[A](body: => A): A =
    ApplicationManager.getApplication
      .executeOnPooledThread(() => body)
      .get(120, TimeUnit.SECONDS)

  private def extractTypedTreeSnapshot(session: PcSession, name: String, source: String): PcTypedTreeSnapshot =
    val snapshot = PcSnapshot(s"file:///$name.scala", 1L, source)
    val outcome  = session.scheduleRetypecheck(snapshot).get(10, TimeUnit.SECONDS)
    assertEquals(RetypecheckOutcome.Applied, outcome)
    session
      .typedTreeSnapshot(snapshot)
      .getOrElse(throw new AssertionError(s"$name typed-tree snapshot was not available"))

  private def assertSnapshotEntry(
      source: String,
      snapshot: PcTypedTreeSnapshot,
      needle: String,
      role: PcTypedTreeRole,
      expectedType: String
  ): Unit =
    val start = source.indexOf(needle)
    assertTrue(s"missing test needle '$needle'", start >= 0)
    val range = PcSourceRange(start, start + needle.length)
    assertTrue(
      s"expected $range $role '$expectedType', got ${snapshot.entries.mkString("\n")}",
      snapshot.entries.exists(entry => entry.range == range && entry.role == role && entry.renderedType == expectedType)
    )

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)

  private def withRealPcSession(prefix: String)(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory(prefix)
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)

    try
      settings.setEnabled(getModule, enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread:
        val session = PcSession.create(
          testScalaVersion.minor,
          moduleClasspath,
          ScalacFlagsService.get(getProject).compilerOptions(getModule),
          fetcher
        )
        try test(session)
        finally session.close()
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def moduleClasspath =
    OrderEnumerator
      .orderEntries(getModule)
      .recursively
      .compileOnly
      .withoutSdk
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new java.io.File(_))
      .toSeq

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()

private[pc] final case class FixedPresentationCompilerResolver(artifact: Path) extends PresentationCompilerResolver:
  override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
    Right(Seq(artifact))

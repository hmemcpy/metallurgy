package com.hmemcpy.metallurgy.testkit

import com.hmemcpy.metallurgy.feature.compilertype.CompilerTypeRequestResolver
import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.hmemcpy.metallurgy.pc.{PcSession, PcSessionManager}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.{MetallurgyStatus, MetallurgyStatusListener}
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.ModuleManager
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParameterizedTypeElement, ScTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScMethodCall, ScReferenceExpression}
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.annotation.tailrec
import scala.jdk.CollectionConverters.*

/** Headless Scala-plugin fixture that runs one source/oracle pair with Metallurgy either enabled or disabled. */
abstract class MetallurgyFixtureTestCase extends ScalaLightCodeInsightFixtureTestCase:

  protected def fixtureName: String

  private var initialBundledCompilerTypes: Option[(Boolean, Boolean)] = None

  override protected def setUp(): Unit =
    super.setUp()
    initialBundledCompilerTypes = Some(bundledCompilerTypeSettings)

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(ScalaVersion.fromString("3.5.2").get)

  protected final def assertMetallurgyOn(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    configureBundledCompilerTypes(enabled = true)
    val context = openFixture()
    // The production fetcher queues its Backgroundable from the EDT; the returned future completes on pooled threads.
    val session = PlatformTestUtil
      .waitForFuture(
        PcSessionManager.get(getProject).prepareFile(context.file.getVirtualFile),
        TimeUnit.SECONDS.toMillis(120)
      )
      .getOrElse(abort(s"Could not prepare PC session for $fixtureName"))
    OracleExecutor(myFixture).assertExpected(context, Some(session), readOracle("expected.metallurgy-on.txt"))

  protected final def assertMetallurgyOff(): Unit =
    ModuleManager
      .getInstance(getProject)
      .getModules
      .foreach: module =>
        MetallurgySettings(getProject).setEnabled(module, enabled = false)
    configureBundledCompilerTypes(enabled = false)
    PcSessionManager.get(getProject).discard(getModule)
    val context = openFixture()
    OracleExecutor(myFixture).assertExpected(context, None, readOracle("expected.metallurgy-off.txt"))

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      initialBundledCompilerTypes.foreach((compilerHighlighting, useCompilerTypes) =>
        setBundledCompilerTypes(compilerHighlighting, useCompilerTypes)
      )
    finally super.tearDown()

  private def openFixture(): FixtureContext =
    val source = Files.readString(fixtureDirectory.resolve("source.scala"), StandardCharsets.UTF_8)
    val file   = configureFromFileText(s"$fixtureName.scala", source)
    val doc    = Option(PsiDocumentManager.getInstance(getProject).getDocument(file)).getOrElse:
      abort(s"No document for $fixtureName")
    FixtureContext(file, doc, source)

  private def readOracle(name: String): List[OracleAssertion] =
    val content = Files.readString(fixtureDirectory.resolve(name), StandardCharsets.UTF_8)
    ExpectedOutputParser.parse(content) match
      case Right(assertions) => assertions
      case Left(error)       => abort(s"Invalid $fixtureName/$name oracle: $error")

  private def fixtureDirectory: Path =
    Path.of(getTestDataPath, "feature", "compilertype", fixtureName)

  private def configureBundledCompilerTypes(enabled: Boolean): Unit =
    setBundledCompilerTypes(enabled, enabled)

  private def setBundledCompilerTypes(compilerHighlighting: Boolean, useCompilerTypes: Boolean): Unit =
    val settingsClass = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val settings      = settingsClass
      .getMethod("getInstance", classOf[com.intellij.openapi.project.Project])
      .invoke(null, getProject)
    val _             = settingsClass
      .getMethod("setCompilerHighlightingScala3", java.lang.Boolean.TYPE)
      .invoke(settings, Boolean.box(compilerHighlighting))
    val _             = settingsClass
      .getMethod("setUseCompilerTypes", java.lang.Boolean.TYPE)
      .invoke(settings, Boolean.box(useCompilerTypes))
    assertEquals((compilerHighlighting, useCompilerTypes), bundledCompilerTypeSettings)

  private def bundledCompilerTypeSettings: (Boolean, Boolean) =
    val settingsClass = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val settings      = settingsClass
      .getMethod("getInstance", classOf[com.intellij.openapi.project.Project])
      .invoke(null, getProject)
    settingsClass.getMethod("isCompilerHighlightingScala3").invoke(settings).asInstanceOf[Boolean] ->
      settingsClass.getMethod("isUseCompilerTypes").invoke(settings).asInstanceOf[Boolean]

  private def abort(message: String): Nothing = throw new AssertionError(message)

private[metallurgy] final case class FixtureContext(file: PsiFile, document: Document, source: String)

private[metallurgy] final class OracleExecutor(fixture: JavaCodeInsightTestFixture):

  def assertExpected(
      context: FixtureContext,
      session: Option[PcSession],
      assertions: List[OracleAssertion]
  ): Unit =
    assertions.foreach(assertion => execute(context, session, assertion))

  private def execute(context: FixtureContext, session: Option[PcSession], assertion: OracleAssertion): Unit =
    assertion match
      case OracleAssertion.Hover(offset, expected)           => assertTypeAt(context, session, offset, expected)
      case OracleAssertion.TypeAt(offset, expected)          => assertTypeAt(context, session, offset, expected)
      case OracleAssertion.Completion(offset, expectedItems) => assertCompletion(offset, expectedItems)
      case OracleAssertion.NotRed(line)                      => assertHighlighting(context, line, expectError = false)
      case OracleAssertion.Red(line)                         => assertHighlighting(context, line, expectError = true)
      case OracleAssertion.Resolve(symbol, target)           => assertResolve(context, symbol, target)

  private def assertTypeAt(
      context: FixtureContext,
      session: Option[PcSession],
      offset: SourceOffset,
      expected: String
  ): Unit =
    val element = semanticElementAt(context.file, offset.value)
    val actual  = session match
      case Some(_) =>
        requestCompilerType(element) match
          case MetallurgyStatus.Resolved(_, _) => Option(BundledPluginBridge.getCompilerType(element))
          case status                          => throw new AssertionError(s"Compiler type request ended with $status")
      case None    => Option(BundledPluginBridge.getCompilerType(element))

    if expected == "<empty>" then
      assertTrue(s"Expected no compiler type at ${offset.value}, got $actual", actual.isEmpty)
    else
      assertEquals(
        s"Compiler type at ${offset.value} for '${element.getText}' (${element.getTextRange})",
        Some(expected),
        actual
      )

  private def assertCompletion(offset: SourceOffset, expectedItems: Set[String]): Unit =
    fixture.getEditor.getCaretModel.moveToOffset(offset.value)
    val actual             = Option(fixture.completeBasic()).toSeq.flatten.map(_.getLookupString).toSet
    val normalizedExpected = expectedItems.map(_.stripPrefix("."))
    assertTrue(
      s"Missing completion items ${normalizedExpected.diff(actual).toSeq.sorted.mkString(", ")}",
      normalizedExpected.subsetOf(actual)
    )

  private def assertHighlighting(context: FixtureContext, line: LineNumber, expectError: Boolean): Unit =
    val zeroBasedLine = line.value - 1
    val start         = context.document.getLineStartOffset(zeroBasedLine)
    val end           = context.document.getLineEndOffset(zeroBasedLine)
    val errors        = fixture
      .doHighlighting()
      .asScala
      .filter: info =>
        info.getSeverity == HighlightSeverity.ERROR && info.getStartOffset <= end && info.getEndOffset >= start
    if expectError then assertTrue(s"Expected an error on line ${line.value}", errors.nonEmpty)
    else assertTrue(s"Unexpected errors on line ${line.value}: ${descriptions(errors)}", errors.isEmpty)

  private def assertResolve(context: FixtureContext, symbol: String, target: String): Unit =
    val offset    = context.source.lastIndexOf(symbol)
    assertTrue(s"Symbol '$symbol' is absent from fixture", offset >= 0)
    val reference = context.file.findReferenceAt(offset)
    assertNotNull(s"No reference at '$symbol'", reference)
    val resolved  = reference.resolve()
    assertNotNull(s"'$symbol' did not resolve", resolved)
    assertTrue(
      s"'$symbol' resolved to '${resolved.getText}', expected target containing '$target'",
      resolved.getText.contains(target)
    )

  private def semanticElementAt(file: PsiFile, offset: Int): PsiElement =
    val leaf    = Option(file.findElementAt(offset)).getOrElse:
      throw new AssertionError(s"No PSI element at offset $offset")
    val parents = Iterator.iterate(leaf)(_.getParent).takeWhile(_ != null).toList
    parents
      .collectFirst { case element: ScParameterizedTypeElement => element }
      .orElse(parents.collectFirst { case element: ScTypeElement => element })
      .orElse:
        parents.collectFirst:
          case reference: ScReferenceExpression => enclosingApplication(reference)
          case element: ScMethodCall            => element
          case element: ScGenericCall           => element
      .getOrElse(leaf.getParent)

  @tailrec
  private def enclosingApplication(element: PsiElement): PsiElement =
    element.getParent match
      case call: ScGenericCall if call.referencedExpr == element => enclosingApplication(call)
      case call: ScMethodCall if call.getInvokedExpr == element  => enclosingApplication(call)
      case _                                                     => element

  private def requestCompilerType(element: PsiElement): MetallurgyStatus =
    val project    = element.getProject
    val completion = new CompletableFuture[MetallurgyStatus]()
    val connection = project.getMessageBus.connect()
    connection.subscribe(
      MetallurgyStatus.Topic,
      new MetallurgyStatusListener:
        override def statusChanged(status: MetallurgyStatus): Unit =
          status match
            case MetallurgyStatus.Resolving(_) | MetallurgyStatus.Enabled => ()
            case terminal                                                 =>
              val _ = completion.complete(terminal)
    )

    try
      CompilerTypeRequestResolver(project).request(element)
      PlatformTestUtil.waitForFuture(completion, TimeUnit.SECONDS.toMillis(120))
    finally connection.disconnect()

  private def descriptions(infos: Iterable[HighlightInfo]): String =
    infos.flatMap(info => Option(info.getDescription)).mkString(", ")

private[metallurgy] object OracleExecutor:
  def apply(fixture: JavaCodeInsightTestFixture): OracleExecutor = new OracleExecutor(fixture)

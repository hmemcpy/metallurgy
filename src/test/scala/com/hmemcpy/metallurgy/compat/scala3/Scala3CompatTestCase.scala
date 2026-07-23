package com.hmemcpy.metallurgy.compat.scala3

import com.hmemcpy.metallurgy.compilerbackend.{CompilerBackendRole, Scala3CompilerBackend}
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.types.result.Failure
import org.jetbrains.plugins.scala.lang.psi.types.{Context, ScTypeExt, TypePresentationContext}
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue, fail}

import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.*

/** Runs Scala 3 cases through the full IntelliJ pipeline: configure the file, let the compiler-backend pass publish its
  * snapshot, then assert on the final PSI state the way the Scala plugin's own fixtures do (`testHighlighting` for
  * errors, `ScExpression.type` for inferred types). The backend is started in setUp; no internal shortcut reads the
  * snapshot directly.
  */
abstract class Scala3CompatTestCase extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  private var savedSettings: (Boolean, Boolean) = (false, false)

  override protected def setUp(): Unit =
    super.setUp()
    val settings = ScalaProjectSettings.getInstance(getProject)
    savedSettings = (settings.isCompilerHighlightingScala3, settings.isUseCompilerTypes)
    settings.setCompilerHighlightingScala3(true)
    settings.setUseCompilerTypes(true)
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      val settings = ScalaProjectSettings.getInstance(getProject)
      settings.setCompilerHighlightingScala3(savedSettings._1)
      settings.setUseCompilerTypes(savedSettings._2)
    finally super.tearDown()

  private val StartMarker = "/*start*/"
  private val EndMarker   = "/*end*/"

  // The backported base aliases START/END to IntelliJ's <selection> tags, which `configureByText` consumes; ported
  // snippets use START/END as expression markers, so alias them to the comment form `selectedExpression` scans for.
  override protected val START: String = StartMarker
  override protected val END: String   = EndMarker

  // The backend population is asynchronous: the first daemon pass starts it and a later pass consumes it. Await it
  // here so the assertion observes the published result instead of the pre-publication bundled read.
  protected def awaitBackendPublished(): Unit =
    val _ = PlatformTestUtil
      .waitForFuture(
        PcSessionManager.get(getProject).prepareCompilerBackend(getFile.getVirtualFile),
        TimeUnit.SECONDS.toMillis(120)
      )
      .getOrElse(
        throw BackendUnavailableException(s"PC backend did not publish a snapshot for ${getFile.getName}")
      )

  // Completion helpers: invoke basic completion at the caret and assert lookup strings are present / absent.
  protected def assertCompletionContains(text: String, expected: String*): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapForHighlighting(text.trim))
    awaitBackendPublished()
    val items   = completionLookupStrings
    val missing = expected.filterNot(items.contains)
    assertTrue(s"completion missing: $missing; got: ${items.toList.sorted.take(20)}", missing.isEmpty)

  protected def assertCompletionExcludes(text: String, excluded: String*): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapForHighlighting(text.trim))
    awaitBackendPublished()
    val items   = completionLookupStrings
    val present = excluded.filter(items.contains)
    assertTrue(s"unexpected completion items present: $present", present.isEmpty)

  protected def assertSmartCompletionContains(text: String, expected: String*): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapForHighlighting(text.trim))
    awaitBackendPublished()
    val items   = Option(myFixture.complete(CompletionType.SMART, 2))
      .map(_.iterator.map(_.getLookupString).toSet)
      .getOrElse(Set.empty)
    val missing = expected.filterNot(items.contains)
    assertTrue(s"smart completion missing: $missing; got: ${items.toList.sorted.take(20)}", missing.isEmpty)

  private def completionLookupStrings: Set[String] =
    Option(myFixture.complete(CompletionType.BASIC, 1))
      .map(_.iterator.map(_.getLookupString).toSet)
      .getOrElse(Set.empty)

  // Runs `checkTextHasNoErrors` over many snippets (the parameterized `SimpleTestData` classes unfold to this) and
  // reports every failing snippet by its 1-based index instead of stopping at the first.
  protected def checkNoErrorsAll(snippets: String*): Unit =
    val failed = snippets.iterator.zipWithIndex
      .flatMap: (code, idx) =>
        var errored = false
        try checkTextHasNoErrors(code.trim)
        catch case _: AssertionError => errored = true
        Option.when(errored)(idx + 1)
      .toList
    if failed.nonEmpty then fail(s"errors in parameterized snippets: ${failed.mkString(", ")}")

  // Mirror of the Scala plugin's no-error assertion, with the async publication awaited between configure and the
  // daemon highlight so the annotator reads the published types.
  override protected def checkTextHasNoErrors(text: String): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapForHighlighting(text.trim))
    awaitBackendPublished()
    val _ = myFixture.testHighlighting(false, false, false, getFile.getVirtualFile)

  override protected def checkHasErrorAroundCaret(text: String): Unit =
    val wrapped                                     = wrapForHighlighting(text.trim)
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapped)
    awaitBackendPublished()
    val caretIndex                                  = wrapped.indexOf(CARET)
    def isAroundCaret(info: HighlightInfo): Boolean =
      caretIndex == -1 || new TextRange(info.getStartOffset, info.getEndOffset).contains(caretIndex)
    val infos                                       = myFixture.doHighlighting().asScala.toList
    val warnings                                    = infos.filter(info => StringUtil.isNotEmpty(info.getDescription) && isAroundCaret(info))
    if shouldPass && warnings.isEmpty then
      fail(s"No matching highlighting around caret. All highlightings:\n${infos.mkString("\n")}")
    else if !shouldPass && warnings.nonEmpty then throw new RuntimeException(failingPassed)

  // Compares the rendered descriptions of every ERROR-severity highlighting the daemon produced, in source order.
  // Parity check on the bundled plugin's ERROR descriptions — the PSI's own static analysis layered on top of the
  // resolved types — which must match whether the PSI's type/resolve slots were filled by the bundled backend or by
  // the pc. dotc's own diagnostics are not compared by content for pass/fail: their presence is asserted, and every
  // difference from the bundled set (wording, dotc-only, or bundled-only) is reported as a warning so the runner can
  // account for it. Over time these warnings map the true PSI/dotc pairings; neither side catches everything the
  // other does.
  protected def assertErrorDescriptions(code: String, expected: String*): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapForHighlighting(code.trim))
    awaitBackendPublished()
    val errors  = myFixture
      .doHighlighting()
      .asScala
      .toList
      .filter(_.`type`.getSeverity(null) == HighlightSeverity.ERROR)
    val bundled = errors.filter(_.isFromAnnotator)
    val dotc    = errors.filter(!_.isFromAnnotator)
    assertEquals(
      s"bundled ERROR descriptions in:\n$code",
      expected.toList,
      bundled.flatMap(info => Option(info.getDescription))
    )
    if expected.nonEmpty then
      assertTrue(s"dotc reported no diagnostic where the bundled plugin reported ${expected.size}", dotc.nonEmpty)
    reportDiagnosticsMismatch(bundled, dotc, code)

  // Emits (does not fail on) the differences between the bundled and dotc ERROR sets, pairing by overlapping range.
  // dotc may surface diagnostics the PSI is oblivious to and vice versa; these are surfaced for review.
  private def reportDiagnosticsMismatch(bundled: List[HighlightInfo], dotc: List[HighlightInfo], code: String): Unit =
    def overlaps(a: HighlightInfo, b: HighlightInfo): Boolean =
      a.getStartOffset < b.getEndOffset && b.getStartOffset < a.getEndOffset
    val paired                                                = for b <- bundled; d <- dotc if overlaps(b, d) yield (b, d)
    val msgMismatch                                           = paired.filter((b, d) => b.getDescription != d.getDescription)
    val bOnly                                                 = bundled.filter(b => !dotc.exists(overlaps(b, _)))
    val dOnly                                                 = dotc.filter(d => !bundled.exists(overlaps(d, _)))
    if bOnly.nonEmpty || dOnly.nonEmpty || msgMismatch.nonEmpty then
      println(s"[parity] bundled/dotc diagnostics differ for:\n$code")
      bOnly.foreach(b =>
        println(s"[parity]   bundled-only @${b.getStartOffset}-${b.getEndOffset}: ${b.getDescription}")
      )
      dOnly.foreach(d =>
        println(s"[parity]   dotc-only     @${d.getStartOffset}-${d.getEndOffset}: ${d.getDescription}")
      )
      msgMismatch.foreach: (b, d) =>
        println(
          s"[parity]   message mismatch @${b.getStartOffset}-${b.getEndOffset}: bundled='${b.getDescription}' | dotc='${d.getDescription}'"
        )

  protected def assertExprType(source: String): Unit =
    val (code, expected) = splitExpected(source)
    myFixture.configureByText(ScalaFileType.INSTANCE, wrapForCompilation(code))
    val file             = getFile.asInstanceOf[ScalaFile]
    awaitBackendPublished()
    val expr             = selectedExpression(file)
    val actual           = renderedType(expr)
    diagnose(expr, actual)
    assertEquals(s"type of '${expr.getText}'", expected, actual)

  // A bare top-level expression is not valid Scala 3, yet many ported snippets end in a reference statement that
  // the bundled resolver types anyway. Wrap such snippets in a method body so the compiler types every statement;
  // A bare top-level expression is not valid Scala 3, yet many ported snippets end in a reference statement that
  // the bundled resolver types anyway.
  //
  // For type-equality cases the snippet is wrapped in a method body so the compiler types every statement; the
  // start/end markers keep assertions offset-independent. Snippets that define their own object/class/trait are left
  // unwrapped, since nesting them would mangle their qualified type names.
  //
  // For no-error and caret-error cases the snippet is wrapped in an object instead: object members may be opaque
  // types, implicit classes, or overloaded extension methods (none of which survive being made local), and bare
  // statements become valid constructor statements. Type-defining snippets are still left unwrapped so that
  // companion-object implicit scope is preserved; name mangling is irrelevant here because these cases assert on
  // highlightings, not on type names.
  protected def wrapForCompilation(code: String): String =
    if definesType(code) then code else wrapInDef(code)

  protected def wrapForHighlighting(code: String): String =
    if definesType(code) then code else wrapInObject(code)

  private def wrapInDef(code: String): String =
    s"def __metallurgy_wrap__ =\n  {\n$code\n  }\n"

  private def wrapInObject(code: String): String =
    s"object __metallurgy_wrap__ {\n$code\n}\n"

  private def definesType(code: String): Boolean =
    code.linesIterator.exists: line =>
      val trimmed = line.trim
      trimmed.startsWith("object ") || trimmed.startsWith("class ") || trimmed.startsWith("implicit class") ||
      trimmed.startsWith("trait ") || trimmed.startsWith("enum ")

  private def splitExpected(source: String): (String, String) =
    val text     = source.trim
    val nl       = text.lastIndexOf('\n')
    val lastLine = (if nl >= 0 then text.substring(nl + 1) else text).trim
    if lastLine.startsWith("//") then
      val inner = (if nl >= 0 then text.substring(0, nl) else "").trim
      (inner, lastLine.substring(2).trim)
    else (text, "")

  private def selectedExpression(file: ScalaFile): ScExpression =
    val text     = file.getText
    val startIdx = text.indexOf(StartMarker)
    assertTrue("missing /*start*/ marker", startIdx >= 0)
    val start    = startIdx + StartMarker.length
    val end      = text.indexOf(EndMarker)
    assertTrue("missing /*end*/ marker", end >= 0)
    val expr     = PsiTreeUtil.findElementOfClassAtRange(file, start, end, classOf[ScExpression])
    assertNotNull(s"no expression between markers in:\n$text", expr)
    expr

  private def renderedType(expr: ScExpression): String =
    val typeResult = expr.`type`() match
      case Right(t) if t.isUnit => expr.getTypeIgnoreBaseType
      case other                => other
    typeResult match
      case Right(t)     =>
        given TypePresentationContext = TypePresentationContext.emptyContextIn(version)
        given Context                 = Context.Empty
        t.presentableText
      case Failure(msg) =>
        throw new AssertionError(s"no inferred type for '${expr.getText}': $msg")

  private def diagnose(expr: ScExpression, psiType: String): Unit =
    val project = expr.getProject
    val module  = ModuleUtilCore.findModuleForPsiElement(expr)
    val fileUrl = expr.getContainingFile.getVirtualFile.getUrl
    val backend = Scala3CompilerBackend.get(project)
    val byRole  = CompilerBackendRole.values.map: role =>
      s"$role=${backend.stateForActiveModule(expr, module, role)}"
    println(s"[diag] expr='${expr.getText}' psiType='$psiType' range=${expr.getTextRange}")
    println(s"[diag] entry: ${backend.describeEntry(module, fileUrl)}")
    println(s"[diag] byRole: ${byRole.mkString(" ")}")
end Scala3CompatTestCase

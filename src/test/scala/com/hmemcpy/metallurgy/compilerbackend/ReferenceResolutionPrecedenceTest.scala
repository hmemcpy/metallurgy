package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.{PcCompilerSymbol, PcSessionManager, PcSnapshotCurrency, PcSourceRange}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.psi.{PsiFile, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertNotSame, assertSame, assertTrue}

final class ReferenceResolutionPrecedenceTest extends ScalaLightCodeInsightFixtureTestCase:

  private final case class Fixture(
      file: PsiFile,
      reference: ScReferenceExpression,
      bundledTarget: com.intellij.psi.PsiElement,
      backend: Scala3CompilerBackend,
      version: Long,
      generation: CompilerBackendGeneration
  )

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    assertTrue(ScalaPluginSemanticBridge.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    finally super.tearDown()

  def testDispatcherSelectsCurrentTarget(): Unit =
    val fixture = referenceFixture("DispatcherCurrentTarget.scala")
    val symbol  = PcCompilerSymbol("Main.CompilerOnly", "CompilerOnly", Set("Method"), None, None)
    commit(fixture, Some(symbol))

    BundledCompilerBackendDispatcher.referenceResolution(fixture.reference) match
      case ReferenceResolutionSelection.Current(Some(target)) =>
        assertEquals("CompilerOnly", target.getName)
        assertNotSame(fixture.bundledTarget, target)
      case selection                                          =>
        throw new AssertionError(s"expected current compiler target, got $selection")

  def testInstalledAdviceReturnsCurrentTargetIdentityAndDiscardsBundledTarget(): Unit =
    val fixture = referenceFixture("CurrentTarget.scala")
    val symbol  = PcCompilerSymbol("Main.CompilerOnly", "CompilerOnly", Set("Method"), None, None)
    commit(fixture, Some(symbol))

    val target   = BundledCompilerBackendDispatcher.referenceResolution(fixture.reference) match
      case ReferenceResolutionSelection.Current(Some(current)) => current
      case selection                                           =>
        throw new AssertionError(s"expected current compiler target, got $selection")
    assertEquals("CompilerOnly", target.getName)
    assertNotSame(fixture.bundledTarget, target)
    val resolved = fixture.reference.multiResolveScala(false)
    assertEquals(1, resolved.length)
    assertSame(target, resolved.head.element)
    assertNotSame(fixture.bundledTarget, resolved.head.element)

  def testDispatcherSelectsCurrentNoTarget(): Unit =
    val fixture = referenceFixture("CurrentNoTarget.scala")
    commit(fixture, None)

    assertEquals(
      ReferenceResolutionSelection.Current(None),
      BundledCompilerBackendDispatcher.referenceResolution(fixture.reference)
    )

  def testInstalledAdviceMapsCurrentNoTargetToEmpty(): Unit =
    val fixture = referenceFixture("AdviceCurrentNoTarget.scala")
    commit(fixture, None)

    assertTrue(fixture.reference.multiResolveScala(false).isEmpty)

  def testPendingReturnsUnknown(): Unit =
    val fixture = referenceFixture("PendingReference.scala")
    fixture.backend.markPending(getModule, fixture.file.getVirtualFile.getUrl, fixture.version, fixture.generation)
    assertUnknown(fixture)

  def testMissingReturnsUnknown(): Unit =
    assertUnknown(referenceFixture("MissingReference.scala"))

  def testStaleReturnsUnknown(): Unit =
    val fixture = referenceFixture("StaleReference.scala")
    val _       = fixture.backend.publish(
      fixture.reference,
      CompilerBackendRole.Reference,
      fixture.version - 1L,
      "String"
    )
    assertUnknown(fixture)

  def testDirectPublicationReturnsUnknown(): Unit =
    val fixture = referenceFixture("DirectReference.scala")
    assertEquals(
      CompilerBackendPublication.Published,
      fixture.backend.publish(fixture.reference, CompilerBackendRole.Reference, fixture.version, "String")
    )

    assertUnknown(fixture)
    assertTrue(fixture.reference.multiResolveScala(false).isEmpty)

  def testSnapshotDocumentVersionMismatchReturnsUnknown(): Unit =
    val fixture = referenceFixture("SnapshotVersionMismatch.scala")
    val symbol  = PcCompilerSymbol("Main.CompilerOnly", "CompilerOnly", Set("Method"), None, None)
    val version = fixture.version - 1L
    fixture.backend.markPending(getModule, fixture.file.getVirtualFile.getUrl, version, fixture.generation)
    assertEquals(
      CompilerBackendCommit.Rejected,
      fixture.backend.commitSnapshotWithMappings(
        getModule,
        fixture.file,
        version,
        fixture.generation,
        Seq(referenceMapping(fixture, Some(symbol)))
      )(PcSnapshotCurrency.Current)
    )

    assertUnknown(fixture)
    assertTrue(fixture.reference.multiResolveScala(false).isEmpty)

  def testSnapshotGenerationMismatchReturnsUnknown(): Unit =
    val fixture = referenceFixture("SnapshotGenerationMismatch.scala")
    val symbol  = PcCompilerSymbol("Main.CompilerOnly", "CompilerOnly", Set("Method"), None, None)
    fixture.backend.markPending(
      getModule,
      fixture.file.getVirtualFile.getUrl,
      fixture.version,
      fixture.generation
    )
    assertEquals(
      CompilerBackendCommit.Rejected,
      fixture.backend.commitSnapshotWithMappings(
        getModule,
        fixture.file,
        fixture.version,
        fixture.generation.copy(session = fixture.generation.session + 1L),
        Seq(referenceMapping(fixture, Some(symbol)))
      )(PcSnapshotCurrency.Current)
    )

    assertUnknown(fixture)
    assertTrue(fixture.reference.multiResolveScala(false).isEmpty)

  def testFailedReturnsUnknown(): Unit =
    val fixture = referenceFixture("FailedReference.scala")
    fixture.backend.markPending(getModule, fixture.file.getVirtualFile.getUrl, fixture.version, fixture.generation)
    fixture.backend.markFailed(getModule, fixture.file.getVirtualFile.getUrl, fixture.version, fixture.generation)
    assertUnknown(fixture)

  def testUnavailableReturnsUnknown(): Unit =
    val fixture = referenceFixture("UnavailableReference.scala")
    fixture.backend.markPending(getModule, fixture.file.getVirtualFile.getUrl, fixture.version, fixture.generation)
    fixture.backend.markUnavailable(getModule, fixture.file.getVirtualFile.getUrl, fixture.version, fixture.generation)
    assertUnknown(fixture)

  def testDispatcherSelectsFallThroughWhenInactive(): Unit =
    val fixture = referenceFixture("InactiveReference.scala", enable = false)

    assertEquals(
      ReferenceResolutionSelection.FallThrough,
      BundledCompilerBackendDispatcher.referenceResolution(fixture.reference)
    )

  def testInactiveReferenceDoesNotAllocateCompilerSession(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    val file               = myFixture.configureByText("InactiveAllocation.scala", "object Existing\nval result = Existing")
    val reference          = PsiTreeUtil.findChildOfType(file, classOf[ScReferenceExpression])
    val sessions           = PcSessionManager.get(getProject)
    val activeBefore       = sessions.activeSessionCount
    val availabilityBefore = sessions.availabilityEntryCount

    assertEquals(
      ReferenceResolutionSelection.FallThrough,
      BundledCompilerBackendDispatcher.referenceResolution(reference)
    )
    assertEquals(1, reference.multiResolveScala(false).length)
    assertEquals(activeBefore, sessions.activeSessionCount)
    assertEquals(availabilityBefore, sessions.availabilityEntryCount)

  def testCompatibilityRenderedTypeReferencesFallThroughWithoutChangingActiveOwnership(): Unit =
    val fixture     = referenceFixture("ActiveOwnershipAfterDetachedParse.scala")
    val typeElement = ScalaPsiElementFactory.createTypeElementFromText(
      "List[String]",
      fixture.reference,
      null
    )
    val references  = PsiTreeUtil.findChildrenOfType(typeElement, classOf[ScStableCodeReference])

    assertTrue(ScalaPsiElementFactory.SyntheticFileKey.isIn(typeElement.getContainingFile))
    assertTrue(!references.isEmpty)
    references.forEach: reference =>
      assertEquals(
        ReferenceResolutionSelection.FallThrough,
        BundledCompilerBackendDispatcher.referenceResolution(reference)
      )
      assertTrue(reference.multiResolveScala(false) != null)
    assertUnknown(fixture)
    assertTrue(fixture.reference.multiResolveScala(false).isEmpty)

  def testInstalledAdviceMapsUnknownToEmpty(): Unit =
    val fixture = referenceFixture("AdviceUnknown.scala")

    assertTrue(fixture.reference.multiResolveScala(false).isEmpty)

  def testInstalledAdviceNullRunsBundledBody(): Unit =
    val fixture = referenceFixture("AdviceInactive.scala", enable = false)

    val resolved = fixture.reference.multiResolveScala(false)
    assertEquals(1, resolved.length)
    assertSame(fixture.bundledTarget, resolved.head.element)

  def testInstalledStableReferenceAdviceReturnsCurrentTargetIdentity(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    val file       = myFixture.configureByText("AdviceStable.scala", "class Existing\nval result: Existing = ???")
    val reference  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScStableCodeReference])
      .stream()
      .filter(_.getText == "Existing")
      .findFirst()
      .orElseThrow()
    val bundled    = reference.multiResolveScala(false)
    assertEquals(1, bundled.length)
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    val range      = reference.getTextRange
    val symbol     = PcCompilerSymbol(
      "Main.CompilerOnlyType",
      "CompilerOnlyType",
      Set("Type"),
      None,
      None,
      isType = true,
      qualifiedName = Some("Main.CompilerOnlyType")
    )
    val mapping    = CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(reference),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      CompilerBackendRole.Reference,
      "CompilerOnlyType",
      Some(symbol.id),
      Some(symbol)
    )
    val backend    = Scala3CompilerBackend.get(getProject)
    val version    = myFixture.getEditor.getDocument.getModificationStamp
    val generation = CompilerBackendGeneration(72L, 72L, 72L)
    backend.markPending(getModule, file.getVirtualFile.getUrl, version, generation)
    assertEquals(
      CompilerBackendCommit.Committed(1),
      backend.commitSnapshotWithMappings(getModule, file, version, generation, Seq(mapping))(PcSnapshotCurrency.Current)
    )
    val target     = BundledCompilerBackendDispatcher.referenceResolution(reference) match
      case ReferenceResolutionSelection.Current(Some(current)) => current
      case selection                                           =>
        throw new AssertionError(s"expected current compiler target, got $selection")

    val resolved = reference.multiResolveScala(false)
    assertEquals(1, resolved.length)
    assertSame(target, resolved.head.element)
    assertNotSame(bundled.head.element, resolved.head.element)

  private def referenceFixture(fileName: String, enable: Boolean = true): Fixture =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    val file          = myFixture.configureByText(fileName, "object Existing\nval result = Existing")
    val reference     = PsiTreeUtil.findChildOfType(file, classOf[ScReferenceExpression])
    val bundledResult = reference.multiResolveScala(false)
    assertEquals(1, bundledResult.length)
    MetallurgySettings(getProject).setEnabled(getModule, enabled = enable)
    Fixture(
      file,
      reference,
      bundledResult.head.element,
      Scala3CompilerBackend.get(getProject),
      myFixture.getEditor.getDocument.getModificationStamp,
      CompilerBackendGeneration(71L, 71L, 71L)
    )

  private def commit(fixture: Fixture, symbol: Option[PcCompilerSymbol]): Unit =
    fixture.backend.markPending(
      getModule,
      fixture.file.getVirtualFile.getUrl,
      fixture.version,
      fixture.generation
    )
    assertEquals(
      CompilerBackendCommit.Committed(1),
      fixture.backend.commitSnapshotWithMappings(
        getModule,
        fixture.file,
        fixture.version,
        fixture.generation,
        Seq(referenceMapping(fixture, symbol))
      )(PcSnapshotCurrency.Current)
    )

  private def referenceMapping(fixture: Fixture, symbol: Option[PcCompilerSymbol]): CompilerBackendMapping =
    val range = fixture.reference.getTextRange
    CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(fixture.reference),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      CompilerBackendRole.Reference,
      "String",
      symbol.map(_.id),
      symbol
    )

  private def assertUnknown(fixture: Fixture): Unit =
    assertEquals(
      ReferenceResolutionSelection.Unknown,
      BundledCompilerBackendDispatcher.referenceResolution(fixture.reference)
    )

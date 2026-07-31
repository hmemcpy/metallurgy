package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.diagnostic.ControlFlowException
import org.junit.Assert.{assertEquals, assertFalse, assertNotEquals, assertTrue}
import org.junit.Test

import java.nio.file.{Files, Path}
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.JarOutputStream

final class Scala3ParserBridgeTest:

  @Test
  def exactArtifactIdentityAndLoaderIdentityAreObservableWithoutExposingTheLoader(): Unit =
    val first  = openBridge()
    val second = openBridge()
    try
      assertEquals(ScalaVersion, first.identity.coordinate.version)
      assertTrue(first.identity.artifacts.nonEmpty)
      assertTrue(first.identity.artifacts.forall(_.sha256.matches("[0-9a-f]{64}")))
      assertNotEquals(first.identity.loader, second.identity.loader)
      assertEquals(Scala3ParserLoaderState.Open, first.loaderState)
      assertEquals(Scala3ParserLoaderState.Open, second.loaderState)
      assertTrue(first.capabilities.requiredUnavailable.isEmpty)
      assertTrue(first.capabilities.publishedParser.isInstanceOf[ParserCapabilityStatus.Unavailable])
    finally
      first.close()
      second.close()

  @Test
  def parserSnapshotContainsOnlyNeutralHostOwnedValues(): Unit =
    val bridge = openBridge()
    try
      val snapshot = parse(
        bridge,
        """package example
          |
          |object Greeting:
          |  def message(name: String): String = s"Hello, $name"
          |""".stripMargin
      )

      assertEquals("PackageDef", snapshot.nodes.find(_.id == snapshot.rootNodeId).map(_.production).orNull)
      assertTrue(snapshot.nodes.exists(node => node.production == "ModuleDef"))
      assertTrue(snapshot.nodes.exists(node => node.production == "DefDef"))
      assertTrue(snapshot.diagnostics.isEmpty)
      assertNeutral(snapshot)
    finally bridge.close()

  @Test
  def positionedSyntaxAndCompilerCommentsAreExactAndDeterministic(): Unit =
    val bridge = openBridge()
    val source =
      """/** api */
        |object Syntax:
        |  // line
        |  inline def value(using count: Int): Int =
        |    /* block */ count
        |""".stripMargin
    try
      val first  = parse(bridge, source)
      val second = parse(bridge, source)

      assertEquals(first, second)
      assertEquals(
        Vector(
          ParserComment(PcSourceRange(0, 10), "/** api */", ParserCommentKind.Doc),
          ParserComment(PcSourceRange(28, 35), "// line", ParserCommentKind.Line),
          ParserComment(PcSourceRange(84, 95), "/* block */", ParserCommentKind.Block)
        ),
        first.comments
      )
      assertTrue(first.positioned.nonEmpty)
      val positionedIds = first.positioned.map(_.id).toSet
      assertTrue(first.positioned.forall(_.production.nonEmpty))
      assertTrue(first.positioned.forall(_.occurrences.nonEmpty))
      assertTrue(
        first.positioned
          .flatMap(_.occurrences)
          .forall(occurrence => first.nodes.exists(_.id == occurrence.ownerNodeId) && occurrence.fieldPath.nonEmpty)
      )
      assertTrue(fieldValues(first).collect { case ParserFieldValue.Positioned(id) => id }.forall(positionedIds))
      assertTrue(first.nodes.find(_.id == first.rootNodeId).exists(_.occurrences.isEmpty))
      assertTrue(first.nodes.filterNot(_.id == first.rootNodeId).forall(_.occurrences.nonEmpty))
      assertNeutral(first)
    finally bridge.close()

  @Test
  def deepPackageSelectionsExportInStrictPreorderWithoutConsumingTheJvmStack(): Unit =
    val bridge = openBridge()
    try
      Vector(1024, 4096).foreach: segmentCount =>
        val source = deepPackageSource(segmentCount)
        val uri    = s"file:///DeepPackage$segmentCount.scala"
        val first  = parse(bridge, source, uri)
        val second = parse(bridge, source, uri)

        assertEquals(first, second)
        assertEquals(ParserSyntaxSnapshot.evidenceFingerprint(first), ParserSyntaxSnapshot.evidenceFingerprint(second))
        assertEquals((0L until first.nodes.size.toLong).toVector, first.nodes.map(_.id))
        assertEquals(1, first.nodes.count(_.production == "PackageDef"))
        assertEquals(segmentCount - 1, first.nodes.count(_.production == "Select"))
        assertEquals("PackageDef", first.nodes.head.production)
        assertEquals(
          ParserNodePosition.Positioned(PcSourceRange(0, source.length), 8, ParserPositionProvenance.SourceDerived),
          first.nodes.head.position
        )
        assertTrue(first.nodes.filter(_.production == "Select").forall(hasStrictSourceSpan(_, source.length)))
        assertTrue(first.nodes.tail.forall(_.occurrences.nonEmpty))
        assertNeutral(first)
    finally bridge.close()

  @Test
  def deepImportSelectionSuspendsInsideRepeatedFieldsWithExactSourceOccurrences(): Unit =
    val bridge       = openBridge()
    val segmentCount = 4096
    val selectedPath = (0 until segmentCount).map(index => s"p$index").mkString(".")
    val source       = s"import $selectedPath.value"
    try
      val first    = parse(bridge, source, "file:///DeepImport.scala")
      val second   = parse(bridge, source, "file:///DeepImport.scala")
      val selects  = first.nodes.filter(_.production == "Select")
      val root     = first.nodes.find(_.id == first.rootNodeId).get
      val imported = first.nodes.find(_.production == "Import").get

      assertEquals(first, second)
      assertEquals(ParserSyntaxSnapshot.evidenceFingerprint(first), ParserSyntaxSnapshot.evidenceFingerprint(second))
      assertEquals(segmentCount - 1, selects.size)
      assertEquals(
        Vector(
          ParserNodeOccurrence(
            root.id,
            Vector(ParserFieldPathSegment.NamedField("stats"), ParserFieldPathSegment.RepeatedIndex(0))
          )
        ),
        imported.occurrences
      )
      assertEquals(
        ParserNodePosition.Positioned(PcSourceRange(0, source.length), 7, ParserPositionProvenance.SourceDerived),
        imported.position
      )
      assertTrue(selects.forall(hasStrictSourceSpan(_, source.length)))
      assertTrue(
        selects
          .flatMap(_.occurrences)
          .forall: occurrence =>
            first.nodes.exists(_.id == occurrence.ownerNodeId) && occurrence.fieldPath.nonEmpty
      )
      assertNeutral(first)
    finally bridge.close()

  @Test
  def cancellationDuringDeepExportLeavesTheBridgeOpenAndReusable(): Unit =
    val bridge       = openBridge()
    val cancellation = new CountingCancellation(256)
    try
      val thrown =
        try
          val _ = bridge.parse(request(deepPackageSource(4096), "file:///CanceledDeepPackage.scala", cancellation))
          false
        catch case _: TestControlFlowException => true

      assertTrue(thrown)
      assertTrue(cancellation.checks.get() > 256)
      assertEquals(Scala3ParserLoaderState.Open, bridge.loaderState)
      val recovered = parse(bridge, "package recovered", "file:///RecoveredPackage.scala")
      assertEquals("PackageDef", recovered.nodes.head.production)
      assertEquals(Scala3ParserLoaderState.Open, bridge.loaderState)
    finally bridge.close()

  @Test
  def closingTheBridgeDetachesItsRuntimeAndRejectsFurtherParsing(): Unit =
    val bridge   = openBridge()
    val identity = bridge.identity
    bridge.close()
    bridge.close()

    assertEquals(identity, bridge.identity)
    assertEquals(Scala3ParserLoaderState.Closed, bridge.loaderState)
    assertEquals(Left(Scala3ParserError.Closed), bridge.parse(request("object Closed")))

  @Test
  def emptyArtifactSetIsRejectedBeforeACompilerLoaderBecomesAvailable(): Unit =
    val result = Scala3ParserBridge.open(coordinate, Seq.empty)
    assertEquals(
      Left(Scala3ParserOpenError.InvalidArtifacts("the exact compiler artifact set is empty")),
      result
    )

  @Test
  def missingCompilerShapeReturnsNamedCapabilityFailures(): Unit =
    val artifact = Files.createTempFile("metallurgy-parser-boundary-", ".jar")
    val output   = new JarOutputStream(Files.newOutputStream(artifact))
    output.close()
    try
      Scala3ParserBridge.open(coordinate, Seq(artifact.toFile)) match
        case Left(Scala3ParserOpenError.MissingCapabilities(identity, capabilities, failures)) =>
          assertEquals(ScalaVersion, identity.coordinate.version)
          assertTrue(failures.exists(_.capability == "context setup"))
          assertTrue(capabilities.requiredUnavailable.nonEmpty)
        case other                                                                             =>
          throw new AssertionError(s"expected named capability failures, found $other")
    finally
      val _ = Files.deleteIfExists(artifact)

  private def openBridge(): Scala3ParserBridge =
    Scala3ParserBridge
      .open(coordinate, compilerDistribution().map(_.toFile))
      .fold(error => throw new AssertionError(error.toString), identity)

  private def parse(bridge: Scala3ParserBridge, source: String): ParserSyntaxSnapshot =
    parse(bridge, source, "file:///ParserBridgeTest.scala")

  private def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(request(source, uri))
      .fold(error => throw new AssertionError(error.toString), identity)

  private def request(source: String): Scala3ParserRequest =
    request(source, "file:///ParserBridgeTest.scala", Scala3ParserCancellation.Never)

  private def request(
      source: String,
      uri: String,
      cancellation: Scala3ParserCancellation = Scala3ParserCancellation.Never
  ): Scala3ParserRequest =
    Scala3ParserRequest(
      ParserSourceUri
        .from(uri)
        .fold(message => throw new AssertionError(message), identity),
      source,
      Vector.empty,
      cancellation
    )

  private def deepPackageSource(segmentCount: Int): String =
    (0 until segmentCount).map(index => s"p$index").mkString("package ", ".", "")

  private def hasStrictSourceSpan(node: ParserSyntaxNode, sourceLength: Int): Boolean =
    node.position match
      case ParserNodePosition.Positioned(range, point, ParserPositionProvenance.SourceDerived) =>
        range.startOffset >= 0 &&
        range.startOffset < range.endOffset &&
        range.endOffset <= sourceLength &&
        point >= range.startOffset &&
        point <= range.endOffset
      case _                                                                                   => false

  private def coordinate: Scala3ParserArtifactCoordinate =
    Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion)

  private def compilerDistribution(): Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve(ScalaVersion)
      .fold(error => throw error.toException, identity)

  private def assertNeutral(value: Any): Unit =
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()

    def visit(current: Any): Unit =
      current match
        case null                                                                        => ()
        case reference: AnyRef if visited.put(reference, java.lang.Boolean.TRUE) != null => ()
        case product: Product                                                            =>
          assertFalse(
            s"exact compiler value escaped: ${product.getClass.getName}",
            product.getClass.getName.startsWith("dotty.tools.")
          )
          product.productIterator.foreach(visit)
        case iterable: Iterable[?]                                                       => iterable.foreach(visit)
        case reference: AnyRef                                                           =>
          assertFalse(
            s"exact compiler value escaped: ${reference.getClass.getName}",
            reference.getClass.getName.startsWith("dotty.tools.")
          )
        case _                                                                           => ()

    visit(value)

  private def fieldValues(snapshot: ParserSyntaxSnapshot): Vector[ParserFieldValue] =
    def descendants(value: ParserFieldValue): Vector[ParserFieldValue] =
      value +: (value match
        case ParserFieldValue.Optional(nested)       => nested.toVector.flatMap(descendants)
        case ParserFieldValue.Repeated(nested)       => nested.flatMap(descendants)
        case ParserFieldValue.Product(_, fields)     => fields.flatMap(field => descendants(field.value))
        case ParserFieldValue.Node(_)                => Vector.empty
        case ParserFieldValue.Positioned(_)          => Vector.empty
        case ParserFieldValue.Name(_)                => Vector.empty
        case ParserFieldValue.GeneratedName(_, _, _) => Vector.empty
        case ParserFieldValue.Scalar(_)              => Vector.empty
        case ParserFieldValue.Unsupported(_)         => Vector.empty
      )
    (snapshot.nodes.flatMap(_.fields) ++ snapshot.positioned.flatMap(_.fields)).flatMap(field =>
      descendants(field.value)
    )

  private val ScalaVersion = "3.7.4"

  private final class CountingCancellation(limit: Int) extends Scala3ParserCancellation:
    val checks = new AtomicInteger(0)

    override def checkCanceled(): Unit =
      if checks.incrementAndGet() > limit then throw new TestControlFlowException

  private final class TestControlFlowException extends RuntimeException("cancelled"), ControlFlowException

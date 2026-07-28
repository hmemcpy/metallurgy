package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertFalse, assertNotEquals, assertTrue}
import org.junit.Test

import java.nio.file.{Files, Path}
import java.util.IdentityHashMap
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
    bridge
      .parse(request(source))
      .fold(error => throw new AssertionError(error.toString), identity)

  private def request(source: String): Scala3ParserRequest =
    Scala3ParserRequest(
      ParserSourceUri
        .from("file:///ParserBridgeTest.scala")
        .fold(message => throw new AssertionError(message), identity),
      source,
      Vector.empty
    )

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

  private val ScalaVersion = "3.7.4"

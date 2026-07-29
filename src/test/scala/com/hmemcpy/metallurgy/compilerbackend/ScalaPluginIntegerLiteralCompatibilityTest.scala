package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.psiproducer.{
  FactStatus,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  SurfaceFactKind,
  TargetRequirement
}
import com.intellij.lang.ASTFactory
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.psi.{PsiElement, PsiFileFactory}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.{CompositeElement, TreeElement}
import com.intellij.psi.stubs.{IndexSink, ObjectStubSerializer, SerializationManagerEx, Stub, StubIndexKey, StubTree}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.{assertEquals, assertTrue}
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScIntegerLiteral
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyIntegerLiteral

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

final class ScalaPluginIntegerLiteralCompatibilityTest extends BasePlatformTestCase:

  def testNativeAndCompatibleTargetsSatisfyTheReviewedContract(): Unit =
    val native  = ScalaPluginSemanticBridge
      .probeNativeIntegerLiterals(getProject)
      .fold(message => throw new AssertionError(message), identity)
    native.foreach: value =>
      Vector(
        "validPsi"            -> value.validPsi,
        "validContainingFile" -> value.validContainingFile,
        "validParent"         -> value.validParent,
        "nodePsiIdentity"     -> value.nodePsiIdentity,
        "projectIdentity"     -> value.projectIdentity,
        "exactTextRange"      -> value.exactTextRange
      ).foreach((name, valid) => assertTrue(s"native $name: $value", valid))
    val catalog = Scala3PsiProductionCatalog
      .withIntegerLiteralTarget(Right(native), () => throw new AssertionError("compatible probe was invoked"))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertTrue(
      catalog.productions
        .find(_.id == "integer-literal-number")
        .exists(_.targetRequirement == TargetRequirement.Native)
    )

    val compatible = ScalaPluginSemanticBridge
      .probeCompatibleIntegerLiterals(getProject)
      .fold(message => throw new AssertionError(message), identity)

    assertEquals(Vector("0", "42", "0x2a", "1_000"), native.map(_.text))
    assertEquals(Vector("0", "42", "42", "1000"), native.map(_.valueText))
    def common(value: NativeIntegerLiteralObservation): NativeIntegerLiteralObservation =
      value.copy(
        implementationSurfaceId = "",
        elementType = "",
        isScalaIntegerLiteralElementType = false,
        compatibleElementTypeIdentity = false
      )
    assertEquals(native.map(common), compatible.map(common))
    assertTrue(native.forall(value => !value.compatibleElementTypeIdentity && value.integerTokenIdentity))
    assertTrue(compatible.forall(value => value.compatibleElementTypeIdentity && value.integerTokenIdentity))
    assertTrue((native ++ compatible).forall(value => !value.stubBasedPsi && !value.stubElementType))
    assertEquals(
      Set("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral"),
      compatible.map(_.implementationSurfaceId).toSet
    )
    assertEquals(
      TargetRequirement.NativeCandidate,
      Scala3PsiProductionCatalog.Reviewed.productions
        .find(_.id == "integer-literal-number")
        .get
        .targetRequirement
    )

    val nativeMismatch    = native.updated(0, native.head.copy(text = "1"))
    val compatibleCatalog = Scala3PsiProductionCatalog
      .withIntegerLiteralTarget(Right(nativeMismatch), () => Right(compatible))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertTrue(
      compatibleCatalog.productions.exists(production =>
        production.id == "integer-literal-number" && production.targetRequirement == TargetRequirement.Compatible
      )
    )

    val installed = ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
    Vector(catalog, compatibleCatalog).foreach: promoted =>
      val surfaces   = installed.withCatalogCapabilities(promoted).rows.map(row => row.id -> row).toMap
      val production = promoted.productions.find(_.id == "integer-literal-number").get
      assertTrue(
        surfaces
          .get(production.targetSurfaceId)
          .exists(row => row.kind == SurfaceFactKind.Element && row.status == FactStatus.Available)
      )
      production.accessors.foreach(accessor =>
        assertTrue(
          accessor.surfaceId,
          surfaces
            .get(accessor.surfaceId)
            .exists(row => row.kind == accessor.surfaceKind && row.status == FactStatus.Available)
        )
      )
    assertTrue(
      Scala3PsiProductionCatalog
        .withIntegerLiteralTarget(Right(nativeMismatch), () => Right(Vector.empty))
        .isLeft
    )

    val invalidCommon = Vector[NativeIntegerLiteralObservation => NativeIntegerLiteralObservation](
      _.copy(contentText = "wrong"),
      _.copy(valueClass = "wrong"),
      _.copy(contentStart = 1),
      _.copy(contentEnd = 0),
      _.copy(isSimpleLiteral = false),
      _.copy(literalTypeIdentity = false),
      _.copy(literalType = "wrong"),
      _.copy(widenedType = "wrong"),
      _.copy(visitorDispatched = false),
      _.copy(visitorElementIdentity = false),
      _.copy(navigationIdentity = false),
      _.copy(validPsi = false),
      _.copy(validContainingFile = false),
      _.copy(validParent = false),
      _.copy(nodePsiIdentity = false),
      _.copy(projectIdentity = false),
      _.copy(exactTextRange = false),
      _.copy(directChildCount = 0),
      _.copy(directChildText = "wrong"),
      _.copy(integerTokenIdentity = false),
      _.copy(stubBasedPsi = true),
      _.copy(stubElementType = true)
    )
    invalidCommon.foreach: invalidate =>
      assertTrue(
        Scala3PsiProductionCatalog
          .withIntegerLiteralTarget(
            Right(native.updated(0, invalidate(native.head))),
            () => Right(compatible.updated(0, invalidate(compatible.head)))
          )
          .isLeft
      )

  def testProbeBoundaryPreservesCancellationAndContainsLinkageFailures(): Unit =
    val linkage = ScalaPluginSemanticBridge.atIntegerLiteralProbeBoundary[Unit]("compatible"):
      throw new NoClassDefFoundError("missing compatible PSI")
    assertEquals(
      Left(
        IntegerLiteralProbeFailure.Unavailable("compatible", "java.lang.NoClassDefFoundError: missing compatible PSI")
      ),
      linkage
    )

    assertControlFlowPropagates:
      ScalaPluginSemanticBridge.atIntegerLiteralProbeBoundary[Unit]("compatible"):
        throw new TestControlFlowException

    assertControlFlowPropagates:
      ScalaPluginSemanticBridge.probeIntegerLiterals(getProject, "native", _ => false): _ =>
        throw new RuntimeException("wrapped", new TestControlFlowException)

  def testCompatibleLiteralContributesNoStubSerializationOrIndexSurface(): Unit =
    def attached(elementType: com.intellij.psi.tree.IElementType): PsiFileImpl =
      val target = ASTFactory.composite(elementType)
      target.rawAddChildren(ASTFactory.leaf(ScalaTokenType.Integer, "42").asInstanceOf[TreeElement])
      val file   = PsiFileFactory
        .getInstance(getProject)
        .createFileFromText("Persistence.scala", Scala3Language.INSTANCE, "val marker = 1\n", false, false)
      file.getNode.getFirstChildNode
      file.getNode.asInstanceOf[CompositeElement].rawAddChildren(target)
      assertTrue(target.getPsi.isInstanceOf[ScIntegerLiteral])
      file.asInstanceOf[PsiFileImpl]

    val nativeTree     = attached(ScalaElementType.IntegerLiteral).calcStubTree
    val compatibleTree = attached(MetallurgyIntegerLiteral.ElementType).calcStubTree

    def stubShape(tree: StubTree): Vector[(String, String)] =
      tree.getPlainList.asScala.toVector.map: stub =>
        val externalId = stub match
          case file: com.intellij.psi.stubs.PsiFileStub[?] =>
            file.getFileElementType.asInstanceOf[com.intellij.psi.tree.IStubFileElementType[?]].getExternalId
          case _                                           => stub.getStubSerializer.getExternalId
        stub.getClass.getName -> externalId
    assertEquals(stubShape(nativeTree), stubShape(compatibleTree))
    assertEquals(nativeTree.getPlainList.size, compatibleTree.getPlainList.size)

    val serialization                           = SerializationManagerEx.getInstanceEx
    def serialized(tree: StubTree): Array[Byte] =
      val output = new ByteArrayOutputStream
      serialization.serialize(tree.getRoot, output)
      output.toByteArray
    val nativeBytes                             = serialized(nativeTree)
    val compatibleBytes                         = serialized(compatibleTree)
    assertTrue(java.util.Arrays.equals(nativeBytes, compatibleBytes))
    val restored                                = new StubTree(
      serialization
        .deserialize(new ByteArrayInputStream(compatibleBytes))
        .asInstanceOf[com.intellij.psi.stubs.PsiFileStub[?]]
    )
    assertEquals(stubShape(nativeTree), stubShape(restored))

    def indexed(tree: StubTree): Vector[(String, String)] =
      val result = ArrayBuffer.empty[(String, String)]
      val sink   = new IndexSink:
        override def occurrence[Psi <: PsiElement, K](indexKey: StubIndexKey[K, Psi], value: K): Unit =
          result += indexKey.toString -> value.toString
      tree.getPlainList.asScala.foreach: stub =>
        Option(stub.getStubSerializer).foreach(
          _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
        )
      result.toVector
    assertEquals(indexed(nativeTree), indexed(compatibleTree))

  private def assertControlFlowPropagates(body: => Any): Unit =
    var propagated = false
    try body
    catch case _: TestControlFlowException => propagated = true
    assertTrue(propagated)

private final class TestControlFlowException extends RuntimeException("cancelled"), ControlFlowException

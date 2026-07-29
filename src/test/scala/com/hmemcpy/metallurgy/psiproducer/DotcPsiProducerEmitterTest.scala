package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.lang.PsiBuilder
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.atomic.AtomicInteger

final class DotcPsiProducerEmitterTest extends ScalaLightCodeInsightFixtureTestCase:

  private val packagingSurface =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl"

  override def getTestDataPath: String = "src/test/testdata"

  def testRejectsUnsupportedPlanFeaturesBeforeOpeningMarkers(): Unit =
    val source           = "x"
    val base             = emitterPlan(source, 2)
    val unsupportedChild = base.copy(composites = base.composites.map: composite =>
      composite.copy(children = composite.children.map(_.copy(placement = ChildPlacement.Wrapped(Vector("owner"))))))
    val unsupportedField = base.copy(composites =
      base.composites.updated(
        0,
        base.composites.head
          .copy(fieldDispositions = Vector(FieldDisposition("value", FieldDispositionKind.Unsupported)))
      )
    )
    val unsupportedToken = base.copy(physicalLeafOwnership =
      base.physicalLeafOwnership.map(
        _.copy(target = TerminalLeafTarget.Token(packagingSurface))
      )
    )
    Vector(unsupportedChild, unsupportedField, unsupportedToken).foreach: plan =>
      val builder = recordingEmitterBuilder(source)
      assertFalse(
        DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, plan, nativeBindings)
      )
      assertEquals(0, builder.getCurrentOffset)

  def testHandlesDeepCompositeNestingWithoutJvmRecursion(): Unit =
    val source  = "x"
    val builder = recordingEmitterBuilder(source)
    val plan    = emitterPlan(source, 10000)
    val targets = plan.targetAssertions.collect:
      case PlannedTargetAssertion(TargetAssertionOwner.Composite(owner), surfaceId, _) => owner -> surfaceId
    DotcPsiProducer.emit(
      plan.composites.head,
      plan.composites.map(value => value.instance -> value).toMap,
      targets.toMap,
      nativeBindings,
      builder
    )
    assertTrue(builder.eof())

  private def recordingEmitterBuilder(source: String): PsiBuilder =
    val offset = new AtomicInteger(0)
    val marker = Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[PsiBuilder.Marker]),
        (_: Object, _: Method, _: Array[Object]) => null
      )
      .asInstanceOf[PsiBuilder.Marker]
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[PsiBuilder]),
        new InvocationHandler:
          override def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object =
            method.getName match
              case "getOriginalText"   => source
              case "rawLookup"         => if arguments(0).asInstanceOf[Int] == 0 then ScalaElementType.PACKAGING else null
              case "rawTokenTypeStart" => Integer.valueOf(0)
              case "mark"              => marker
              case "eof"               => java.lang.Boolean.valueOf(offset.get() >= source.length)
              case "getCurrentOffset"  => Integer.valueOf(offset.get())
              case "advanceLexer"      => offset.set(source.length); null
              case "toString"          => "recording emitter builder"
              case "hashCode"          => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"            => java.lang.Boolean.valueOf(proxy eq arguments(0))
              case _                   => throw new UnsupportedOperationException(method.toString)
      )
      .asInstanceOf[PsiBuilder]

  private def nativeBindings: NativePsiElementBindings =
    NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)

  private def emitterPlan(source: String, depth: Int): WholeFileProductionPlan =
    val ids        = Vector.tabulate(depth)(index => ProductionInstanceId(InventoryKind.Node, index + 1L, None))
    val position   = ParserNodePosition.Positioned(
      PcSourceRange(0, source.length),
      0,
      ParserPositionProvenance.SourceDerived
    )
    val composites = ids.zipWithIndex.map: (id, index) =>
      val children = ids
        .lift(index + 1)
        .toVector
        .map: child =>
          PlannedChild("child", Vector.empty, child, ChildPlacement.Direct)
      PlannedComposite(id, "test", position, children, Vector.empty)
    WholeFileProductionPlan(
      ParserSourceUri.from("file:///EmitterCase.scala").toOption.get,
      ParserSyntaxSnapshot.digest(source),
      "test",
      Vector(PlannedPhysicalLeaf(1L, 0, source.length, ids.last, "source", TerminalLeafTarget.Parent)),
      Vector.empty,
      composites,
      ids.map(id =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(id),
          packagingSurface,
          TargetAssertionKind.NativeComposite
        )
      ),
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

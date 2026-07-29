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
    Vector(unsupportedField, unsupportedToken).foreach: plan =>
      val builder = recordingEmitterBuilder(source)
      assertFalse(
        DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, plan, nativeBindings)
      )
      assertEquals(0, builder.getCurrentOffset)

    val widerSource  = "xy"
    val wider        = emitterPlan(widerSource, 1)
    val outsideOwner = wider.copy(composites = wider.composites.map(_.copy(range = PcSourceRange(0, 1))))
    val builder      = recordingEmitterBuilder(widerSource)
    assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, outsideOwner, nativeBindings))
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

  def testAcceptsTwoOrderedForestRootsAndRejectsOverlapBeforeMarkers(): Unit =
    val source   = "xy"
    val base     = emitterPlan(source, 1)
    val first    = base.composites.head.copy(range = PcSourceRange(0, 1))
    val secondId = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 2L, None), "self")
    val second   = first.copy(instance = secondId, range = PcSourceRange(1, 2))
    val forest   = base.copy(
      physicalLeafOwnership = Vector(
        PlannedPhysicalLeaf(
          1L,
          0,
          1,
          PhysicalLeafOwner.FileRoot,
          first.instance.origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(
          2L,
          1,
          2,
          PhysicalLeafOwner.FileRoot,
          second.instance.origin,
          "source",
          TerminalLeafTarget.Parent
        )
      ),
      composites = Vector(second, first),
      targetAssertions = Vector(first.instance, second.instance).map(id =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(id),
          packagingSurface,
          TargetAssertionKind.NativeComposite
        )
      )
    )
    assertTrue(
      DotcPsiProducer.parse(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source),
        forest,
        nativeBindings
      )
    )

    val malformed = forest.copy(composites = Vector(first.copy(range = PcSourceRange(0, 2)), second))
    val builder   = recordingEmitterBuilder(source)
    assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, malformed, nativeBindings))
    assertEquals(0, builder.getCurrentOffset)

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
              case "rawLookup"         =>
                if arguments(0).asInstanceOf[Int] < source.length then ScalaElementType.PACKAGING else null
              case "rawTokenTypeStart" => Integer.valueOf(arguments(0).asInstanceOf[Int])
              case "mark"              => marker
              case "eof"               => java.lang.Boolean.valueOf(offset.get() >= source.length)
              case "getCurrentOffset"  => Integer.valueOf(offset.get())
              case "advanceLexer"      => offset.incrementAndGet(); null
              case "toString"          => "recording emitter builder"
              case "hashCode"          => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"            => java.lang.Boolean.valueOf(proxy eq arguments(0))
              case _                   => throw new UnsupportedOperationException(method.toString)
      )
      .asInstanceOf[PsiBuilder]

  private def nativeBindings: NativePsiElementBindings =
    NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)

  private def emitterPlan(source: String, depth: Int): WholeFileProductionPlan =
    val origins    = Vector.tabulate(depth)(index => ProductionInstanceId(InventoryKind.Node, index + 1L, None))
    val ids        = origins.map(CompositeInstanceId(_, "self"))
    val position   = PcSourceRange(0, source.length)
    val composites = ids.zipWithIndex.map: (id, index) =>
      val children = ids
        .lift(index + 1)
        .toVector
        .map: child =>
          PlannedChild("child", Vector.empty, child)
      PlannedComposite(id, "test", position, children, Vector.empty)
    WholeFileProductionPlan(
      ParserSourceUri.from("file:///EmitterCase.scala").toOption.get,
      ParserSyntaxSnapshot.digest(source),
      "test",
      Vector(
        PlannedPhysicalLeaf(
          1L,
          0,
          source.length,
          PhysicalLeafOwner.Composite(ids.last),
          ids.last.origin,
          "source",
          TerminalLeafTarget.Parent
        )
      ),
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

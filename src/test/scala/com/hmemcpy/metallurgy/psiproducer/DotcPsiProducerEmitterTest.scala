package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.lang.PsiBuilder
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.atomic.AtomicInteger

final class DotcPsiProducerEmitterTest extends ScalaLightCodeInsightFixtureTestCase:

  private val packagingSurface =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl"
  private val packagingRole    = PsiOutputRoleId.PackageStatement.value

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
    val unknownOwner     = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 999L, None), "missing")
    val malformedStub    = base.copy(stubAssertions =
      Vector(
        PlannedStubAssertion(unknownOwner, "stub", "serializer", Vector("index"), "navigation")
      )
    )
    Vector(unsupportedField, unsupportedToken, malformedStub).foreach: plan =>
      val builder = recordingEmitterBuilder(source)
      assertFalse(
        DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, plan, nativeBindings)
      )
      assertEquals(0, builder.getCurrentOffset)

    val unboundRole = base.copy(targetAssertions =
      base.targetAssertions.map(
        _.copy(surfaceId = "scala.output.unbound")
      )
    )
    val roleBuilder = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, roleBuilder, unboundRole, nativeBindings)
    )
    assertEquals(0, roleBuilder.getCurrentOffset)

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

  def testRejectsTerminalTextMismatchBeforeOpeningMarkers(): Unit =
    val source    = "x"
    val base      = emitterPlan(source, 1)
    val origin    = base.composites.head.instance.origin
    val terminal  = "wildcard"
    val malformed = base.copy(
      physicalLeafOwnership = base.physicalLeafOwnership.map(
        _.copy(
          sourceOwner = origin,
          terminalId = terminal,
          target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("*"))
        )
      ),
      targetAssertions = base.targetAssertions :+ PlannedTargetAssertion(
        TargetAssertionOwner.Terminal(origin, terminal),
        NativePsiElementBindings.ImportWildcardTokenSurface,
        TargetAssertionKind.Token
      )
    )
    val builder   = recordingEmitterBuilder(source)
    assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, malformed, nativeBindings))
    assertEquals(0, builder.getCurrentOffset)
    assertEquals(
      Left("terminal token text differs from plan"),
      DotcPsiProducer.parseResult(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source),
        malformed,
        nativeBindings
      )
    )

    val mismatchedSurface = malformed.copy(
      physicalLeafOwnership = malformed.physicalLeafOwnership.map(
        _.copy(
          target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("x"))
        )
      ),
      targetAssertions = malformed.targetAssertions.map:
        case assertion @ PlannedTargetAssertion(TargetAssertionOwner.Terminal(_, _), _, _) =>
          assertion.copy(surfaceId = NativePsiElementBindings.ImportAliasAsTokenSurface)
        case assertion                                                                     => assertion
    )
    val surfaceBuilder    = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, surfaceBuilder, mismatchedSurface, nativeBindings)
    )
    assertEquals(0, surfaceBuilder.getCurrentOffset)

  def testOneTerminalTargetContractBindsEveryValidatedOccurrence(): Unit =
    val source   = "*,*"
    val base     = emitterPlan(source, 1)
    val origin   = base.composites.head.instance.origin
    val terminal = "wildcards"
    val target   = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("*"))
    val plan     = base.copy(
      physicalLeafOwnership = Vector(
        PlannedPhysicalLeaf(atom(1), 0, 1, PhysicalLeafOwner.FileRoot, origin, terminal, target),
        PlannedPhysicalLeaf(
          atom(2),
          1,
          2,
          PhysicalLeafOwner.FileRoot,
          origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(atom(3), 2, 3, PhysicalLeafOwner.FileRoot, origin, terminal, target)
      ),
      targetAssertions = base.targetAssertions :+ PlannedTargetAssertion(
        TargetAssertionOwner.Terminal(origin, terminal),
        NativePsiElementBindings.ImportWildcardTokenSurface,
        TargetAssertionKind.Token
      )
    )
    assertTrue(PlannedScala3Lexer.compile(source, plan, nativeBindings).isRight)

    val duplicatedContract = plan.copy(targetAssertions = plan.targetAssertions :+ plan.targetAssertions.last)
    val builder            = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, duplicatedContract, nativeBindings)
    )
    assertEquals(0, builder.getCurrentOffset)

  def testNativeBindingsRejectAnOutputRoleBoundToAnotherHostSurface(): Unit =
    val catalog = Scala3PsiProductionCatalog(
      Scala3PsiProductionCatalog.Reviewed.productions.map:
        case production if production.id == "file-package" =>
          production.copy(outputRoleId = Some(PsiOutputRoleId.ImportSelector))
        case production                                    => production
    )
    assertTrue(nativeBindings.validate(catalog).isLeft)

  def testRejectsMissingOrUnexpectedBoundOutputObligationsBeforeOpeningMarkers(): Unit =
    val source      = "x"
    val base        = emitterPlan(source, 1)
    val owner       = base.composites.head.instance
    val accessor    = AccessorObligation("accessor", required = true)
    val persistence = PersistenceObligations.Required("stub", "serializer", Vector("index"), "stub-navigation")
    val contract    =
      NativeOutputContract(packagingSurface, Vector(accessor), persistence, Some(NavigationObligation.Self))
    val bindings    = nativeBindings.copy(outputContracts = Map(PsiOutputRoleId.PackageStatement -> contract))
    val complete    = base.copy(
      accessorAssertions = Vector(PlannedAccessorAssertion(owner, accessor.surfaceId, accessor.required)),
      stubAssertions = Vector(PlannedStubAssertion(owner, "stub", "serializer", Vector("index"), "stub-navigation")),
      navigationAssertions = Vector(PlannedNavigationAssertion(owner, NavigationObligation.Self))
    )
    assertTrue(
      DotcPsiProducer.parse(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source),
        complete,
        bindings
      )
    )
    Vector(
      complete.copy(accessorAssertions = Vector.empty),
      complete.copy(accessorAssertions = complete.accessorAssertions :+ PlannedAccessorAssertion(owner, "extra", true)),
      complete.copy(stubAssertions = Vector.empty),
      complete.copy(stubAssertions = complete.stubAssertions.map(_.copy(serializerSurfaceId = "wrong"))),
      complete.copy(navigationAssertions = Vector.empty)
    ).foreach: malformed =>
      val builder = recordingEmitterBuilder(source)
      assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, malformed, bindings))
      assertEquals(0, builder.getCurrentOffset)

  def testPlanBackedLexerOwnsExactSourceWithoutBundledLexer(): Unit =
    val source     = "import a.b.{c /* => */ => d}\n"
    val arrowStart = source.lastIndexOf("=>")
    val base       = emitterPlan(source, 1)
    val origin     = base.composites.head.instance.origin
    val plan       = base.copy(physicalLeafOwnership =
      Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          arrowStart,
          PhysicalLeafOwner.FileRoot,
          origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(
          atom(2),
          arrowStart,
          arrowStart + 2,
          PhysicalLeafOwner.FileRoot,
          origin,
          "alias",
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasArrowTokenSurface, Some("=>"))
        ),
        PlannedPhysicalLeaf(
          atom(3),
          arrowStart + 2,
          source.length,
          PhysicalLeafOwner.FileRoot,
          origin,
          "source",
          TerminalLeafTarget.Parent
        )
      )
    )
    val lexer      = PlannedScala3Lexer.compile(source, plan, nativeBindings).toOption.get
    lexer.start(source, 0, source.length, 0)
    val observed   = Vector.newBuilder[(String, com.intellij.psi.tree.IElementType)]
    while lexer.getTokenType != null do
      observed += source.substring(lexer.getTokenStart, lexer.getTokenEnd) -> lexer.getTokenType
      lexer.advance()
    val tokens     = observed.result()
    assertEquals(source, tokens.map(_._1).mkString)
    assertEquals(ScalaTokenTypes.kIMPORT, tokens.head._2)
    assertTrue(
      tokens.contains("=>" -> nativeBindings.elementTypes(NativePsiElementBindings.ImportAliasArrowTokenSurface))
    )
    assertTrue(new Scala3DotcParserDefinition().createLexer(getProject).isInstanceOf[PlannedScala3Lexer])

  def testPlanBackedLexerRejectsMalformedTokenTargetsBeforeBuilderConstruction(): Unit =
    val source        = "x"
    val base          = emitterPlan(source, 1)
    val leaf          = base.physicalLeafOwnership.head.copy(
      target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface)
    )
    val invalidRanges = Vector(leaf.copy(end = 0), leaf.copy(start = -1), leaf.copy(end = 2))
    assertEquals(
      Vector(
        LexerPlanFailure.InvalidTargetRange(0, 0, 1),
        LexerPlanFailure.InvalidTargetRange(-1, 1, 1),
        LexerPlanFailure.InvalidTargetRange(0, 2, 1)
      ),
      invalidRanges.map(value =>
        PlannedScala3Lexer
          .compile(source, base.copy(physicalLeafOwnership = Vector(value)), nativeBindings)
          .left
          .toOption
          .get
      )
    )
    assertEquals(
      Some(LexerPlanFailure.DuplicateTargetStart(0)),
      PlannedScala3Lexer
        .compile(source, base.copy(physicalLeafOwnership = Vector(leaf, leaf.copy(atomId = atom(2)))), nativeBindings)
        .left
        .toOption
    )
    assertEquals(
      Some(LexerPlanFailure.UnsupportedTargetSurface("missing")),
      PlannedScala3Lexer
        .compile(
          source,
          base.copy(physicalLeafOwnership = Vector(leaf.copy(target = TerminalLeafTarget.Token("missing")))),
          nativeBindings
        )
        .left
        .toOption
    )
    assertEquals(
      Some(LexerPlanFailure.LexicalContractMismatch),
      PlannedScala3Lexer
        .compile(
          source,
          base.copy(lexicalContract = ClosedSourceLexicalContract.from(source + " ")),
          nativeBindings
        )
        .left
        .toOption
    )
    val unsafeSource  = "ab"
    val unsafeBase    = emitterPlan(unsafeSource, 1)
    val unsafeLeaf    = unsafeBase.physicalLeafOwnership.head.copy(
      end = 1,
      target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface)
    )
    assertEquals(
      Some(LexerPlanFailure.UnsafeTargetBoundary(0, 1)),
      PlannedScala3Lexer
        .compile(unsafeSource, unsafeBase.copy(physicalLeafOwnership = Vector(unsafeLeaf)), nativeBindings)
        .left
        .toOption
    )
    val overlapSource = "x y"
    val overlapBase   = emitterPlan(overlapSource, 1)
    val first         = overlapBase.physicalLeafOwnership.head.copy(
      end = 3,
      target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface)
    )
    val second        = first.copy(atomId = atom(2), start = 2)
    assertEquals(
      Some(LexerPlanFailure.OverlappingTargetRanges(0, 3, 2, 3)),
      PlannedScala3Lexer
        .compile(overlapSource, overlapBase.copy(physicalLeafOwnership = Vector(first, second)), nativeBindings)
        .left
        .toOption
    )

  def testAcceptsTwoOrderedForestRootsAndRejectsOverlapBeforeMarkers(): Unit =
    val source   = "xy"
    val base     = emitterPlan(source, 1)
    val first    = base.composites.head.copy(range = PcSourceRange(0, 1))
    val secondId = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 2L, None), "self")
    val second   = first.copy(instance = secondId, range = PcSourceRange(1, 2))
    val forest   = base.copy(
      physicalLeafOwnership = Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          1,
          PhysicalLeafOwner.FileRoot,
          first.instance.origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(
          atom(2),
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
          packagingRole,
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
    NativePsiElementBindings
      .probe(getProject)
      .fold(error => throw new AssertionError(error), identity)
      .copy(outputContracts =
        Map(
          PsiOutputRoleId.PackageStatement -> NativeOutputContract(
            packagingSurface,
            Vector.empty,
            PersistenceObligations.NotApplicable,
            None
          )
        )
      )

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
      ClosedSourceLexicalContract.from(source),
      Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          source.length,
          PhysicalLeafOwner.Composite(ids.last),
          ids.last.origin,
          "source",
          TerminalLeafTarget.Parent
        )
      ),
      Vector.empty,
      Vector.empty,
      composites,
      ids.map(id =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(id),
          packagingRole,
          TargetAssertionKind.NativeComposite
        )
      ),
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

  private def atom(id: Long): SourceAtomId = SourceAtomId(id, 0)

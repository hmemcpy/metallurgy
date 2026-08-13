package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{ParserSyntaxSnapshot, PcSessionManager, PcSourceRange}
import com.hmemcpy.metallurgy.psiproducer.Scala3ParserPreparationLifecycle
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.plugins.scala.{Scala3Language, ScalaVersion}
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition
import org.junit.Assert.{assertEquals, assertFalse, assertTrue, fail}

import java.nio.charset.StandardCharsets

final class ScalaPluginPhysicalPsiBridgeTest extends ScalaLightCodeInsightFixtureTestCase:
  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean = true

  def testDirectValAndDefNamedCallsProduceEqualNeutralWitnesses(): Unit =
    Vector(
      ("val value = f(name = x)\n", PhysicalPsiOwnerRole.ValueDefinition),
      ("def value = f(name = x)\n", PhysicalPsiOwnerRole.FunctionDefinition)
    ).foreach: (source, owner) =>
      val result = inspect(request(source, "f(name = x)", "name = x", "name", "=", "x", owner))
      result match
        case ScalaPluginPhysicalPsiResult.Equal(witness) =>
          assertEquals(owner, witness.ownerRole)
          assertEquals(Scala3Language.INSTANCE.getID, "Scala 3")
          assertTrue(witness.capability.facts.contains("registered-scala3-parser-definition"))
          assertTrue(witness.nodes.exists(_.roles.contains(PhysicalPsiNodeRole.MethodCall)))
          assertTrue(witness.nodes.exists(_.roles.contains(PhysicalPsiNodeRole.ArgumentList)))
          assertTrue(witness.nodes.exists(_.roles.contains(PhysicalPsiNodeRole.NamedArgument)))
          assertEquals(
            Vector(PhysicalPsiNodeRole.MethodCall, PhysicalPsiNodeRole.Expression, PhysicalPsiNodeRole.DirectRhs),
            witness.nodes.head.roles
          )
        case other                                       => fail(other.toString)

  def testSelectedNestedAndCurriedCallsRemainWitnessStructures(): Unit =
    Vector(
      "val value = source.f(name = x)\n"                          -> "source.f(name = x)",
      "val value = outer(inner(name = x), source.f(other = y))\n" ->
        "outer(inner(name = x), source.f(other = y))",
      "val value = f(first = x)(second = y)\n"                    -> "f(first = x)(second = y)"
    ).foreach: (source, rhs) =>
      val named = namedEvidence(source, rhs)
      inspect(request(source, rhs, named)) match
        case ScalaPluginPhysicalPsiResult.Equal(witness) =>
          assertEquals(named.size, witness.namedArguments.size)
          val expectedCalls = if rhs.startsWith("source") then 1 else if rhs.startsWith("outer") then 3 else 2
          assertEquals(expectedCalls, witness.nodes.count(_.roles.contains(PhysicalPsiNodeRole.MethodCall)))
          assertTrue(
            witness.nodes
              .filter(_.roles.contains(PhysicalPsiNodeRole.NamedArgumentName))
              .forall(_.roles.contains(PhysicalPsiNodeRole.Reference))
          )
        case other                                       => fail(other.toString)

  def testCommentsWhitespacePunctuationAndLineBreaksReconstructExactly(): Unit =
    val source =
      """val value = f(
        |  first /* name */ = /* value */ x,
        |  second=
        |    y
        |)
        |""".stripMargin
    val rhs    = source.substring(source.indexOf("f("), source.lastIndexOf(')') + 1)
    inspect(request(source, rhs, namedEvidence(source, rhs))) match
      case ScalaPluginPhysicalPsiResult.Equal(witness) =>
        assertEquals(rhs, new String(witness.reconstructedUtf8.toArray, StandardCharsets.UTF_8))
        assertEquals(ParserSyntaxSnapshot.digest(rhs), witness.reconstructionHash)
        assertTrue(witness.leaves.exists(_.role == PhysicalPsiLeafRole.Comment))
        assertTrue(witness.leaves.exists(_.role == PhysicalPsiLeafRole.Whitespace))
        assertTrue(witness.leaves.exists(_.role == PhysicalPsiLeafRole.Comma))
        assertTrue(witness.leaves.exists(_.role == PhysicalPsiLeafRole.Equals))
      case other                                       => fail(other.toString)

  def testMalformedAndWrongRangesNeverProduceEqual(): Unit =
    val malformed = Vector(
      "val value = f(name x)\n",
      "val value = f(name = x\n",
      "val value = f(name =)\n"
    )
    malformed.foreach: source =>
      val start = source.indexOf("f(")
      val rhs   = source.substring(start).stripSuffix("\n")
      assertConflict(request(source, rhs, Vector.empty))

    val valid = "val value = f(name = x)\n"
    val wrong = request(valid, "f(name = x)", namedEvidence(valid, "f(name = x)"))
    assertConflict(wrong.copy(dotc = wrong.dotc.copy(directRhsRange = PcSourceRange(0, valid.length))))

  def testStaleDigestVersionAndCapabilityAreUnavailable(): Unit =
    val source       = "val value = f(name = x)\n"
    val current      = request(source, "f(name = x)", namedEvidence(source, "f(name = x)"))
    val staleDigest  = current.copy(source = current.source.copy(digest = "stale"))
    assertUnavailable(staleDigest)
    val staleVersion = current.copy(dotc = current.dotc.copy(source = current.source.copy(documentVersion = 2L)))
    assertUnavailable(staleVersion)
    val signature    = inspect(current) match
      case ScalaPluginPhysicalPsiResult.Equal(witness) => witness.capability.signature
      case other                                       => fail(other.toString); ""
    assertUnavailable(current.copy(expectedCapabilitySignature = Some(signature.reverse)))

  def testAmbiguousCandidatesAreConflictWithReadableRanges(): Unit =
    val source  = "val value = f(name = x)\n"
    val req     = request(source, "f(name = x)", namedEvidence(source, "f(name = x)"))
    val witness = inspect(req) match
      case ScalaPluginPhysicalPsiResult.Equal(value) => value
      case other                                     => fail(other.toString); throw new AssertionError
    ScalaPluginPhysicalPsiBridge.compare(req, complete(Vector(witness, witness))) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
        assertTrue(differences.head.startsWith("extra direct RHS candidates"))
        assertTrue(differences(1).startsWith("candidate ranges:"))
      case other                                              => fail(other.toString)

  def testPositionalUsingAndTypeApplyFamiliesAreExplicitShapeConflicts(): Unit =
    Vector(
      "val value = f(x)\n"             -> "f(x)",
      "val value = f(using x)\n"       -> "f(using x)",
      "val value = f[Int](name = x)\n" -> "f[Int](name = x)"
    ).foreach: (source, rhs) =>
      val expectedNamed =
        if rhs.contains("name") then namedEvidence(source, rhs)
        else
          Vector(
            DotcNamedArgumentEvidence(
              PcSourceRange(0, 1),
              PcSourceRange(0, 1),
              PcSourceRange(0, 1),
              PcSourceRange(0, 1)
            )
          )
      assertConflict(request(source, rhs, expectedNamed))

  def testParentChildRangesAndLeafOrderAreExact(): Unit =
    val source = "class C:\n  val value = f(first = x, second = y)\n"
    val rhs    = "f(first = x, second = y)"
    inspect(request(source, rhs, namedEvidence(source, rhs))) match
      case ScalaPluginPhysicalPsiResult.Equal(witness) =>
        val byId                = witness.nodes.map(node => node.id -> node).toMap
        assertEquals(None, witness.nodes.head.parentId)
        witness.nodes.tail.foreach(node => assertTrue(node.parentId.exists(byId.contains)))
        witness.nodes.foreach(node => node.childIds.foreach(child => assertEquals(Some(node.id), byId(child).parentId)))
        assertEquals(witness.rhsRange.startOffset, witness.leaves.head.range.startOffset)
        assertEquals(witness.rhsRange.endOffset, witness.leaves.last.range.endOffset)
        witness.leaves
          .sliding(2)
          .foreach:
            case Vector(left, right) => assertEquals(left.range.endOffset, right.range.startOffset)
            case _                   => ()
        ScalaPluginPhysicalPsiBridge.compare(
          request(source, rhs, namedEvidence(source, rhs)),
          complete(Vector(witness.copy(leaves = witness.leaves.reverse)))
        ) match
          case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
            assertTrue(differences.exists(_.startsWith("unowned or overlapping leaf bytes")))
          case other                                              => fail(s"reordered leaves must conflict, found $other")
        val withoutInteriorLeaf = witness.leaves.patch(1, Nil, 1)
        assertLeafConflict(source, rhs, witness.copy(leaves = withoutInteriorLeaf), "unowned or overlapping leaf bytes")
        val overlapping         = witness.leaves.updated(
          1,
          witness
            .leaves(1)
            .copy(
              range = PcSourceRange(witness.leaves.head.range.startOffset, witness.leaves(1).range.endOffset),
              sourceSlice = source.substring(witness.leaves.head.range.startOffset, witness.leaves(1).range.endOffset)
            )
        )
        assertLeafConflict(source, rhs, witness.copy(leaves = overlapping), "unowned or overlapping leaf bytes")
        val duplicateRoles      = witness.nodes.updated(
          0,
          witness.nodes.head.copy(roles = witness.nodes.head.roles :+ witness.nodes.head.roles.head)
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = duplicateRoles), "duplicate role claims")
        val incompatibleRoles   = witness.nodes.updated(
          0,
          witness.nodes.head.copy(
            roles = Vector(
              PhysicalPsiNodeRole.MethodCall,
              PhysicalPsiNodeRole.NamedArgument,
              PhysicalPsiNodeRole.Expression,
              PhysicalPsiNodeRole.DirectRhs
            )
          )
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = incompatibleRoles), "incompatible intrinsic role claims")
        val argumentListIndex   = witness.nodes.indexWhere(_.roles.contains(PhysicalPsiNodeRole.ArgumentList))
        val crossFamily         = witness.nodes.updated(
          argumentListIndex,
          witness
            .nodes(argumentListIndex)
            .copy(
              roles = Vector(PhysicalPsiNodeRole.ArgumentList, PhysicalPsiNodeRole.InvokedExpression)
            )
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = crossFamily), "role schema disallows InvokedExpression")
        val invokedIndex        = witness.nodes.indexWhere(_.roles.contains(PhysicalPsiNodeRole.InvokedExpression))
        val invalidRelation     = witness.nodes.updated(
          invokedIndex,
          witness
            .nodes(invokedIndex)
            .copy(
              roles = Vector(
                PhysicalPsiNodeRole.Reference,
                PhysicalPsiNodeRole.Expression,
                PhysicalPsiNodeRole.NamedArgumentName
              )
            )
        )
        assertTreeConflict(
          source,
          rhs,
          witness.copy(nodes = invalidRelation),
          "invalid named-argument-name relation"
        )
        val reorderedRoles      = witness.nodes.updated(0, witness.nodes.head.copy(roles = witness.nodes.head.roles.reverse))
        assertTreeConflict(source, rhs, witness.copy(nodes = reorderedRoles), "noncanonical role order")
        assertTreeConflict(
          source,
          rhs,
          witness.copy(nodes = witness.nodes :+ witness.nodes.head),
          "duplicate node record id"
        )
        val bounds              = PhysicalPsiFactBounds.forSourceUtf16Length(source.length)
        val boundedNodes        = Vector.tabulate(bounds.exportedNodes)(id =>
          witness.nodes.head.copy(id = id, parentId = None, childIds = Vector.empty)
        )
        val atLimit             = conflictDifferences(source, rhs, witness.copy(nodes = boundedNodes))
        assertFalse(atLimit.exists(_.contains("exceeds source-derived limit")))
        val overLimit           = conflictDifferences(
          source,
          rhs,
          witness.copy(nodes = boundedNodes :+ boundedNodes.last.copy(id = bounds.exportedNodes))
        )
        assertEquals(
          Vector(
            s"node fact count ${bounds.exportedNodes + 1} exceeds source-derived limit ${bounds.exportedNodes}"
          ),
          overLimit
        )
        val deepNodes           = Vector.tabulate(bounds.depth + 2): id =>
          val last = id == bounds.depth + 1
          PhysicalPsiNodeFact(
            id,
            if id == 0 then witness.nodes.head.roles
            else Vector(PhysicalPsiNodeRole.Syntax),
            witness.rhsRange,
            Option.when(id > 0)(id - 1),
            Option.when(!last)(Vector(id + 1)).getOrElse(Vector.empty)
          )
        assertTreeConflict(source, rhs, witness.copy(nodes = deepNodes), "tree depth")
        assertTreeConflict(
          source,
          rhs,
          witness.copy(source = witness.source.copy(documentVersion = 2L)),
          "changed source identities"
        )
        val detachedId          = witness.nodes.map(_.id).max + 1
        val detached            = PhysicalPsiNodeFact(
          detachedId,
          Vector(PhysicalPsiNodeRole.Syntax),
          witness.rhsRange,
          None,
          Vector.empty
        )
        assertTreeConflict(
          source,
          rhs,
          witness.copy(nodes = witness.nodes :+ detached),
          "changed tree roots"
        )
        val repeatedChild       = witness.nodes.updated(
          0,
          witness.nodes.head.copy(childIds = witness.nodes.head.childIds :+ witness.nodes.head.childIds.head)
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = repeatedChild), "duplicate child relation")
        val partitionParent     = witness.nodes.find(_.childIds.size >= 2).get
        val partitionChildIndex = witness.nodes.indexWhere(_.id == partitionParent.childIds.head)
        val partitionChild      = witness.nodes(partitionChildIndex)
        val childGap            = witness.nodes.updated(
          partitionChildIndex,
          partitionChild.copy(
            range = PcSourceRange(partitionChild.range.startOffset, partitionChild.range.endOffset - 1)
          )
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = childGap), "changed child partition")
        val childOverlap        = witness.nodes.updated(
          partitionChildIndex,
          partitionChild.copy(
            range = PcSourceRange(partitionChild.range.startOffset, partitionChild.range.endOffset + 1)
          )
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = childOverlap), "changed child partition")
        val nestedParentIndex   = witness.nodes.indexWhere(node => node.id != 0 && node.childIds.nonEmpty)
        val nestedParent        = witness.nodes(nestedParentIndex)
        val changedParent       = witness.nodes.updated(
          nestedParentIndex,
          nestedParent.copy(range = PcSourceRange(nestedParent.range.startOffset, nestedParent.range.endOffset - 1))
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = changedParent), "changed child partition")
        val orphanId            = detachedId + 1
        val orphan              = detached.copy(id = orphanId, parentId = Some(orphanId + 100))
        assertTreeConflict(source, rhs, witness.copy(nodes = witness.nodes :+ orphan), "unreachable node record id")
        val cycleLeftId         = orphanId + 1
        val cycleRightId        = cycleLeftId + 1
        val cycleNodes          = Vector(
          detached.copy(id = cycleLeftId, parentId = Some(cycleRightId), childIds = Vector(cycleRightId)),
          detached.copy(id = cycleRightId, parentId = Some(cycleLeftId), childIds = Vector(cycleLeftId))
        )
        assertTreeConflict(
          source,
          rhs,
          witness.copy(nodes = witness.nodes ++ cycleNodes),
          "cycle detected among node ids"
        )
        val firstLeaf           = witness.leaves.head
        assertTreeConflict(
          source,
          rhs,
          witness.copy(leaves = witness.leaves.updated(0, firstLeaf.copy(nodeId = witness.nodes.head.id))),
          "leaf names nonterminal node"
        )
        assertTreeConflict(source, rhs, witness.copy(leaves = witness.leaves.tail), "terminal node")
        assertTreeConflict(source, rhs, witness.copy(leaves = firstLeaf +: witness.leaves), "terminal node")
        val wrongRange          = firstLeaf.copy(
          range = PcSourceRange(firstLeaf.range.startOffset, firstLeaf.range.endOffset + 1),
          sourceSlice = source.substring(firstLeaf.range.startOffset, firstLeaf.range.endOffset + 1)
        )
        assertTreeConflict(
          source,
          rhs,
          witness.copy(leaves = witness.leaves.updated(0, wrongRange)),
          "changed leaf/node range"
        )
        val terminalIndex       = witness.nodes.indexWhere(_.id == firstLeaf.nodeId)
        val incompatibleLeaf    = witness.nodes.updated(
          terminalIndex,
          witness.nodes(terminalIndex).copy(roles = Vector(PhysicalPsiNodeRole.Expression))
        )
        assertTreeConflict(source, rhs, witness.copy(nodes = incompatibleLeaf), "leaf-incompatible roles")
      case other                                       => fail(other.toString)

  def testProbeUsesOrdinaryParserWithoutCreatingMetallurgyServices(): Unit =
    val parser             = LanguageParserDefinitions.INSTANCE.forLanguage(Scala3Language.INSTANCE)
    assertTrue(parser.isInstanceOf[Scala3ParserDefinition])
    val serviceClasses     = Vector(
      classOf[ModuleDetectionService],
      classOf[PcSessionManager],
      classOf[Scala3ParserPreparationLifecycle]
    )
    val before             = serviceClasses.map(getProject.getServiceIfCreated)
    val modificationBefore = PsiModificationTracker.getInstance(getProject).getModificationCount
    val source             = "val value = f(name = x)\n"
    inspect(request(source, "f(name = x)", namedEvidence(source, "f(name = x)"))) match
      case ScalaPluginPhysicalPsiResult.Equal(witness) =>
        assertTrue(witness.capability.facts.contains("ordinary-scala3-language"))
        assertTrue(witness.capability.facts.contains("nonphysical-unregistered-no-events"))
        assertFalse(witness.capability.implementationEvidence.exists(_.contains("Scala3Dotc")))
      case other                                       => fail(other.toString)
    assertEquals(before, serviceClasses.map(getProject.getServiceIfCreated))
    assertEquals(modificationBefore, PsiModificationTracker.getInstance(getProject).getModificationCount)

  def testPureFactBudgetAcceptsExactLimitsAndRejectsPlusOne(): Unit =
    val source  = "val value = f(name = x)\n"
    val req     = request(source, "f(name = x)", namedEvidence(source, "f(name = x)"))
    val context = PhysicalPsiLimitContext(Vector(0), PcSourceRange(0, 0))
    val bounds  = PhysicalPsiFactBounds(1, 1, 1, 1, 1, 1, 1, 1, 1, 100)

    def assertLimitConflict(outcome: Option[PhysicalPsiExtractionOutcome.LimitExceeded]): Unit =
      val exceeded = outcome.getOrElse:
        fail("plus one must exceed its limit")
        throw new AssertionError
      var phases   = Vector.empty[PhysicalPsiComparisonPhase]
      ScalaPluginPhysicalPsiBridge.compareForTest(req, exceeded, phase => phases :+= phase) match
        case ScalaPluginPhysicalPsiResult.Conflict(differences) => assertTrue(differences.nonEmpty)
        case other                                              => fail(s"limit excess must conflict, found $other")
      assertEquals(Vector.empty, phases)

    Vector(
      PhysicalPsiFactCategory.WorkUnit,
      PhysicalPsiFactCategory.ExportedNode,
      PhysicalPsiFactCategory.Edge,
      PhysicalPsiFactCategory.Leaf,
      PhysicalPsiFactCategory.NamedArgument,
      PhysicalPsiFactCategory.ParserError,
      PhysicalPsiFactCategory.CandidateRoot,
      PhysicalPsiFactCategory.GenericCall
    ).foreach: category =>
      val budget   = new PhysicalPsiFactBudget(bounds)
      assertEquals(None, budget.charge(category, context))
      val exceeded = budget.charge(category, context)
      exceeded match
        case Some(PhysicalPsiExtractionOutcome.LimitExceeded(observed, limit, actual, actualContext)) =>
          assertEquals(2L, observed)
          assertEquals(1, limit)
          assertEquals(category.description, actual)
          assertEquals(context, actualContext)
        case other                                                                                    => fail(s"${category.description} plus one must exceed its limit, found $other")
      assertLimitConflict(exceeded)

    val depthBudget   = new PhysicalPsiFactBudget(bounds)
    assertEquals(None, depthBudget.observeDepth(1, context))
    val depthExceeded = depthBudget.observeDepth(2, context)
    depthExceeded match
      case Some(PhysicalPsiExtractionOutcome.LimitExceeded(2L, 1, "depth", actualContext)) =>
        assertEquals(context, actualContext)
      case other                                                                           =>
        fail(s"depth plus one must exceed its limit, found $other")
    assertLimitConflict(depthExceeded)

    val totalBudget   = new PhysicalPsiFactBudget(bounds.copy(workUnits = 2, totalFacts = 1))
    assertEquals(None, totalBudget.charge(PhysicalPsiFactCategory.WorkUnit, context))
    val totalExceeded = totalBudget.charge(PhysicalPsiFactCategory.WorkUnit, context)
    totalExceeded match
      case Some(PhysicalPsiExtractionOutcome.LimitExceeded(2L, 1, "total fact", actualContext)) =>
        assertEquals(context, actualContext)
      case other                                                                                =>
        fail(s"total facts plus one must exceed its limit, found $other")
    assertLimitConflict(totalExceeded)

  def testTraversalLimitPlusOneStopsBeforeEqualityReconstructionAndHash(): Unit =
    val source        = "val value = f(name = x)\n"
    val req           = request(source, "f(name = x)", namedEvidence(source, "f(name = x)"))
    val defaultBounds = PhysicalPsiFactBounds.forSourceUtf16Length(source.length)
    var usage         = Option.empty[PhysicalPsiTraversalUsage]
    ScalaPluginPhysicalPsiBridge.inspectForTest(
      getProject,
      req,
      defaultBounds,
      _ => (),
      value => usage = Some(value)
    ) match
      case _: ScalaPluginPhysicalPsiResult.Equal => ()
      case other                                 => fail(other.toString)
    val exact         = usage.getOrElse:
      fail("missing traversal usage")
      throw new AssertionError
    val exactBounds   = defaultBounds.copy(
      workUnits = exact.workUnits,
      exportedNodes = exact.exportedNodes,
      edges = exact.edges,
      leaves = exact.leaves,
      namedArguments = exact.namedArguments,
      parserErrors = exact.parserErrors,
      candidateRoots = exact.candidateRoots,
      genericCalls = exact.genericCalls,
      depth = exact.maximumDepth,
      totalFacts = exact.totalFacts
    )
    ScalaPluginPhysicalPsiBridge.inspectForTest(getProject, req, exactBounds, _ => (), _ => ()) match
      case _: ScalaPluginPhysicalPsiResult.Equal => ()
      case other                                 => fail(s"exact traversal limits must pass, found $other")

    var comparisonPhases = Vector.empty[PhysicalPsiComparisonPhase]
    val overLimit        = exactBounds.copy(workUnits = exact.workUnits - 1)
    ScalaPluginPhysicalPsiBridge.inspectForTest(
      getProject,
      req,
      overLimit,
      phase => comparisonPhases :+= phase,
      _ => fail("an incomplete traversal must not publish usage")
    ) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
        assertTrue(differences.head.startsWith("work unit count"))
        assertTrue(differences.head.contains(s"${overLimit.workUnits + 1}"))
        assertTrue(differences.head.contains(s"limit ${overLimit.workUnits}"))
      case other                                              => fail(s"limit excess must conflict, found $other")
    assertEquals(Vector.empty, comparisonPhases)

    comparisonPhases = Vector.empty
    ScalaPluginPhysicalPsiBridge.inspectForTest(
      getProject,
      req,
      exactBounds.copy(totalFacts = exact.totalFacts - 1),
      phase => comparisonPhases :+= phase,
      _ => fail("an incomplete traversal must not publish usage")
    ) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
        assertTrue(differences.head.startsWith("total fact count"))
      case other                                              => fail(s"total limit excess must conflict, found $other")
    assertEquals(Vector.empty, comparisonPhases)

    val genericSource = "val value = f[Int](name = x)\n"
    val genericRhs    = "f[Int](name = x)"
    val genericReq    = request(genericSource, genericRhs, namedEvidence(genericSource, genericRhs))
    comparisonPhases = Vector.empty
    ScalaPluginPhysicalPsiBridge.inspectForTest(
      getProject,
      genericReq,
      PhysicalPsiFactBounds.forSourceUtf16Length(genericSource.length).copy(genericCalls = 0),
      phase => comparisonPhases :+= phase,
      _ => fail("an incomplete traversal must not publish usage")
    ) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
        assertTrue(differences.head.startsWith("generic call fact count 1"))
      case other                                              => fail(s"generic-call limit excess must conflict, found $other")
    assertEquals(Vector.empty, comparisonPhases)

  def testNeutralFactBudgetCategoriesStopBeforeStructuralEquality(): Unit =
    val source      = "val value = f(name = x)\n"
    val rhs         = "f(name = x)"
    val req         = request(source, rhs, namedEvidence(source, rhs))
    val witness     = inspect(req) match
      case ScalaPluginPhysicalPsiResult.Equal(value) => value
      case other                                     => fail(other.toString); throw new AssertionError
    val bounds      = PhysicalPsiFactBounds.forSourceUtf16Length(source.length)
    val root        = witness.nodes.head
    val parserError = PhysicalPsiParserError(witness.rhsRange, "bounded")
    val base        = extraction(Vector(witness))
    val mutations   = Vector(
      "work unit"           -> base.copy(traversal = base.traversal.copy(workUnits = bounds.workUnits + 1)),
      "node fact"           -> extraction(Vector(witness.copy(nodes = Vector.fill(bounds.exportedNodes + 1)(root)))),
      "child relation"      -> extraction(
        Vector(witness.copy(nodes = witness.nodes.updated(0, root.copy(childIds = Vector.fill(bounds.edges + 1)(1)))))
      ),
      "leaf fact"           -> extraction(Vector(witness.copy(leaves = Vector.fill(bounds.leaves + 1)(witness.leaves.head)))),
      "named argument fact" -> extraction(
        Vector(witness.copy(namedArguments = Vector.fill(bounds.namedArguments + 1)(witness.namedArguments.head)))
      ),
      "parser error fact"   -> extraction(
        Vector(witness.copy(parserErrors = Vector.fill(bounds.parserErrors + 1)(parserError)))
      ),
      "candidate root"      -> base.copy(
        traversal = base.traversal.copy(candidateRoots = bounds.candidateRoots + 1)
      ),
      "generic call fact"   -> extraction(Vector(witness.copy(genericCallCount = bounds.genericCalls + 1))),
      "depth"               -> base.copy(traversal = base.traversal.copy(maximumDepth = bounds.depth + 1)),
      "total fact"          -> base.copy(traversal = base.traversal.copy(totalFacts = bounds.totalFacts + 1))
    )
    mutations.foreach: (category, extraction) =>
      assertBudgetConflict(req, extraction, category)

  def testMatchingMalformedNamedEvidenceConflictsBeforeReconstructionAndHash(): Unit =
    val source                = "val value = f(first = x, second = y)\n"
    val rhs                   = "f(first = x, second = y)"
    val witness               = inspect(request(source, rhs, namedEvidence(source, rhs))) match
      case ScalaPluginPhysicalPsiResult.Equal(value) => value
      case other                                     => fail(other.toString); throw new AssertionError
    val first                 = witness.namedArguments.head
    val equalsLeafIndex       = witness.leaves.indexWhere(_.range == first.equalsRange)
    val triviaLeafIndex       = witness.leaves.indexWhere(leaf =>
      leaf.range.startOffset >= first.nameRange.endOffset &&
        leaf.range.endOffset <= first.valueRange.startOffset &&
        leaf.role == PhysicalPsiLeafRole.Whitespace
    )
    val nameNodeIndex         = witness.nodes.indexWhere(node =>
      node.range == first.nameRange && node.roles.contains(PhysicalPsiNodeRole.NamedArgumentName)
    )
    assertTrue(equalsLeafIndex >= 0)
    assertTrue(triviaLeafIndex >= 0)
    assertTrue(nameNodeIndex >= 0)
    val reversedRangeRejected =
      try Option(PcSourceRange(first.valueRange.endOffset, first.valueRange.startOffset)).isEmpty
      catch case _: IllegalArgumentException => true
    assertTrue("reversed ranges must be rejected before neutral comparison", reversedRangeRejected)

    val outOfBounds = first.copy(
      argumentRange = PcSourceRange(first.argumentRange.startOffset, source.length + 1),
      valueRange = PcSourceRange(first.valueRange.startOffset, source.length + 1)
    )
    val overlap     = first.copy(
      valueRange = PcSourceRange(first.equalsRange.startOffset, first.valueRange.endOffset)
    )
    val cases       = Vector(
      (
        "out-of-bounds",
        witness.namedArguments.updated(0, outOfBounds),
        (value: ScalaPluginPhysicalPsiWitness) => value,
        Vector.empty
      ),
      (
        "reversed-overlap",
        witness.namedArguments.updated(0, overlap),
        (value: ScalaPluginPhysicalPsiWitness) => value,
        Vector(PhysicalPsiComparisonPhase.StructuralEquality)
      ),
      (
        "missing-equals",
        witness.namedArguments,
        (value: ScalaPluginPhysicalPsiWitness) =>
          value.copy(
            leaves = value.leaves.updated(
              equalsLeafIndex,
              value.leaves(equalsLeafIndex).copy(role = PhysicalPsiLeafRole.Whitespace)
            )
          ),
        Vector(PhysicalPsiComparisonPhase.StructuralEquality)
      ),
      (
        "extra-equals",
        witness.namedArguments,
        (value: ScalaPluginPhysicalPsiWitness) =>
          value.copy(
            leaves = value.leaves.updated(
              triviaLeafIndex,
              value.leaves(triviaLeafIndex).copy(role = PhysicalPsiLeafRole.Equals, sourceSlice = "=")
            )
          ),
        Vector(PhysicalPsiComparisonPhase.StructuralEquality)
      ),
      (
        "wrong-equals-slice",
        witness.namedArguments,
        (value: ScalaPluginPhysicalPsiWitness) =>
          value.copy(leaves =
            value.leaves.updated(equalsLeafIndex, value.leaves(equalsLeafIndex).copy(sourceSlice = "x"))
          ),
        Vector(PhysicalPsiComparisonPhase.StructuralEquality)
      ),
      (
        "name-node-mismatch",
        witness.namedArguments,
        (value: ScalaPluginPhysicalPsiWitness) =>
          value.copy(
            nodes = value.nodes.updated(
              nameNodeIndex,
              value
                .nodes(nameNodeIndex)
                .copy(range = PcSourceRange(first.nameRange.startOffset, first.nameRange.endOffset + 1))
            )
          ),
        Vector(PhysicalPsiComparisonPhase.StructuralEquality)
      ),
      (
        "reordered",
        witness.namedArguments.reverse,
        (value: ScalaPluginPhysicalPsiWitness) => value,
        Vector(PhysicalPsiComparisonPhase.StructuralEquality)
      )
    )
    cases.foreach: (label, named, mutate, expectedPhases) =>
      val dotc   = named.map(value =>
        DotcNamedArgumentEvidence(value.argumentRange, value.nameRange, value.equalsRange, value.valueRange)
      )
      val req    = request(source, rhs, dotc)
      var phases = Vector.empty[PhysicalPsiComparisonPhase]
      ScalaPluginPhysicalPsiBridge.compareForTest(
        req,
        complete(Vector(mutate(witness.copy(namedArguments = named)))),
        phase => phases :+= phase
      ) match
        case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
          assertTrue(
            s"$label must produce a named-evidence conflict, found ${differences.mkString("; ")}",
            differences.exists(value =>
              value.startsWith("invalid dotc named argument") ||
                value.startsWith("invalid witness named argument") ||
                value.startsWith("reordered or overlapping dotc named argument")
            )
          )
        case other                                              => fail(s"matching malformed named evidence must conflict, found $other")
      assertEquals(expectedPhases, phases)

  def testOutOfSourceStructuralRangesConflictBeforeReconstructionAndHash(): Unit =
    val source  = "val value = f(name = x)\n"
    val rhs     = "f(name = x)"
    val req     = request(source, rhs, namedEvidence(source, rhs))
    val witness = inspect(req) match
      case ScalaPluginPhysicalPsiResult.Equal(value) => value
      case other                                     => fail(other.toString); throw new AssertionError
    val outside = PcSourceRange(source.length, source.length + 1)
    val cases   = Vector(
      "dotc RHS"     -> (req.copy(dotc = req.dotc.copy(directRhsRange = outside)), witness),
      "witness RHS"  -> (req, witness.copy(rhsRange = outside)),
      "node"         -> (req, witness.copy(nodes = witness.nodes.updated(0, witness.nodes.head.copy(range = outside)))),
      "leaf"         -> (req, witness.copy(leaves = witness.leaves.updated(0, witness.leaves.head.copy(range = outside)))),
      "parser error" -> (req, witness.copy(parserErrors = Vector(PhysicalPsiParserError(outside, "outside"))))
    )
    cases.foreach: (label, values) =>
      val (caseRequest, caseWitness) = values
      var phases                     = Vector.empty[PhysicalPsiComparisonPhase]
      ScalaPluginPhysicalPsiBridge.compareForTest(
        caseRequest,
        complete(Vector(caseWitness)),
        phase => phases :+= phase
      ) match
        case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
          assertTrue(
            s"$label must fail source bounds, found ${differences.mkString("; ")}",
            differences.exists(_.contains("outside exact source"))
          )
        case other                                              => fail(s"$label outside source must conflict, found $other")
      assertEquals(Vector.empty, phases)

  def testContractIsDtoOnlyAndDoesNotUseSemanticSurfaces(): Unit =
    val dtoClasses = Vector(
      classOf[PhysicalPsiSourceIdentity],
      classOf[DotcNamedArgumentEvidence],
      classOf[DotcPhysicalPsiEvidence],
      classOf[PhysicalPsiNodeFact],
      classOf[PhysicalPsiLeafFact],
      classOf[PhysicalPsiNamedArgumentFact],
      classOf[PhysicalPsiParserError],
      classOf[ScalaPluginPhysicalPsiCapability],
      classOf[ScalaPluginPhysicalPsiWitness],
      classOf[PhysicalPsiTraversalUsage],
      classOf[PhysicalPsiExtractionWitness],
      classOf[PhysicalPsiLimitContext]
    )
    dtoClasses
      .flatMap(_.getDeclaredFields)
      .foreach: field =>
        val name = field.getType.getName
        assertFalse(
          s"native PSI/AST field escaped: ${field.getDeclaringClass.getName}.${field.getName}: $name",
          name.startsWith("com.intellij.psi") || name.startsWith("com.intellij.lang.AST")
        )

    val source = java.nio.file.Files.readString(
      java.nio.file.Path.of(
        "src",
        "main",
        "scala",
        "com",
        "hmemcpy",
        "metallurgy",
        "compilerbackend",
        "ScalaPluginPhysicalPsiBridge.scala"
      )
    )
    Vector(
      "PsiTreeUtil",
      ".getChildren(",
      ".isNamedParameter",
      ".bind(",
      ".resolve(",
      ".getType(",
      ".matchedParameters"
    ).foreach: forbidden =>
      assertFalse(s"semantic surface must not be used: $forbidden", source.contains(forbidden))

  private def inspect(request: ScalaPluginPhysicalPsiRequest): ScalaPluginPhysicalPsiResult =
    ScalaPluginPhysicalPsiBridge.inspect(getProject, request)

  private def assertConflict(request: ScalaPluginPhysicalPsiRequest): Unit =
    inspect(request) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) => assertTrue(differences.nonEmpty)
      case other                                              => fail(s"expected Conflict, found $other")

  private def assertUnavailable(request: ScalaPluginPhysicalPsiRequest): Unit =
    inspect(request) match
      case ScalaPluginPhysicalPsiResult.Unavailable(reasons) => assertTrue(reasons.nonEmpty)
      case other                                             => fail(s"expected Unavailable, found $other")

  private def assertLeafConflict(
      source: String,
      rhs: String,
      witness: ScalaPluginPhysicalPsiWitness,
      expectedDifference: String
  ): Unit =
    assertTreeConflict(source, rhs, witness, expectedDifference)

  private def assertTreeConflict(
      source: String,
      rhs: String,
      witness: ScalaPluginPhysicalPsiWitness,
      expectedDifference: String
  ): Unit =
    val differences = conflictDifferences(source, rhs, witness)
    assertTrue(
      s"expected conflict starting with '$expectedDifference', found ${differences.mkString("; ")}",
      differences.exists(_.startsWith(expectedDifference))
    )

  private def conflictDifferences(
      source: String,
      rhs: String,
      witness: ScalaPluginPhysicalPsiWitness
  ): Vector[String] =
    ScalaPluginPhysicalPsiBridge.compare(
      request(source, rhs, namedEvidence(source, rhs)),
      complete(Vector(witness))
    ) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) => differences
      case other                                              => fail(s"invalid neutral tree must conflict, found $other"); Vector.empty

  private def assertBudgetConflict(
      request: ScalaPluginPhysicalPsiRequest,
      witness: PhysicalPsiExtractionWitness,
      category: String
  ): Unit =
    var phases = Vector.empty[PhysicalPsiComparisonPhase]
    ScalaPluginPhysicalPsiBridge.compareForTest(
      request,
      PhysicalPsiExtractionOutcome.Complete(witness),
      phase => phases :+= phase
    ) match
      case ScalaPluginPhysicalPsiResult.Conflict(differences) =>
        assertTrue(differences.head.startsWith(s"$category count"))
      case other                                              => fail(s"$category excess must conflict, found $other")
    assertEquals(Vector.empty, phases)

  private def request(
      source: String,
      rhs: String,
      argument: String,
      name: String,
      equals: String,
      value: String,
      ownerRole: PhysicalPsiOwnerRole
  ): ScalaPluginPhysicalPsiRequest =
    request(source, rhs, Vector(argumentEvidence(source, argument, name, equals, value)), ownerRole)

  private def request(
      source: String,
      rhs: String,
      named: Vector[DotcNamedArgumentEvidence],
      ownerRole: PhysicalPsiOwnerRole = PhysicalPsiOwnerRole.ValueDefinition
  ): ScalaPluginPhysicalPsiRequest =
    val identity =
      PhysicalPsiSourceIdentity("file:///PhysicalPsiWitness.scala", ParserSyntaxSnapshot.digest(source), 1L)
    ScalaPluginPhysicalPsiRequest(
      identity,
      source,
      DotcPhysicalPsiEvidence(identity, ownerRole, rangeOf(source, rhs), named)
    )

  private def namedEvidence(source: String, rhs: String): Vector[DotcNamedArgumentEvidence] =
    val rhsStart = source.indexOf(rhs)
    val pattern  =
      "([A-Za-z_$][A-Za-z0-9_$]*)(?:\\s|/\\*.*?\\*/)*=((?:\\s|/\\*.*?\\*/)*)((?:[A-Za-z_$][A-Za-z0-9_$]*))".r
    pattern
      .findAllMatchIn(rhs)
      .map: matched =>
        val argumentStart = rhsStart + matched.start
        val nameStart     = rhsStart + matched.start(1)
        val equalsStart   =
          rhsStart + matched.end(1) + matched.group(0).substring(matched.end(1) - matched.start).indexOf('=')
        val valueStart    = rhsStart + matched.start(3)
        DotcNamedArgumentEvidence(
          PcSourceRange(argumentStart, rhsStart + matched.end),
          PcSourceRange(nameStart, nameStart + matched.group(1).length),
          PcSourceRange(equalsStart, equalsStart + 1),
          PcSourceRange(valueStart, valueStart + matched.group(3).length)
        )
      .toVector

  private def argumentEvidence(
      source: String,
      argument: String,
      name: String,
      equals: String,
      value: String
  ): DotcNamedArgumentEvidence =
    val argumentRange = rangeOf(source, argument)
    DotcNamedArgumentEvidence(
      argumentRange,
      relativeRange(source, argumentRange, name),
      relativeRange(source, argumentRange, equals),
      relativeRange(source, argumentRange, value)
    )

  private def relativeRange(source: String, within: PcSourceRange, text: String): PcSourceRange =
    val offset = source.substring(within.startOffset, within.endOffset).indexOf(text)
    PcSourceRange(within.startOffset + offset, within.startOffset + offset + text.length)

  private def rangeOf(source: String, text: String): PcSourceRange =
    val start = source.indexOf(text)
    assertTrue(s"missing fixture slice: $text", start >= 0)
    PcSourceRange(start, start + text.length)

  private def complete(candidates: Vector[ScalaPluginPhysicalPsiWitness]): PhysicalPsiExtractionOutcome =
    PhysicalPsiExtractionOutcome.Complete(extraction(candidates))

  private def extraction(candidates: Vector[ScalaPluginPhysicalPsiWitness]): PhysicalPsiExtractionWitness =
    val nodes          = candidates.iterator.map(_.nodes.size).sum
    val edges          = candidates.iterator.flatMap(_.nodes).map(_.childIds.size).sum
    val leaves         = candidates.iterator.map(_.leaves.size).sum
    val named          = candidates.iterator.map(_.namedArguments.size).sum
    val errors         = candidates.iterator.map(_.parserErrors.size).sum
    val generic        = candidates.iterator.map(_.genericCallCount).sum
    val candidateCount = candidates.size
    PhysicalPsiExtractionWitness(
      candidates,
      PhysicalPsiTraversalUsage(
        nodes,
        nodes,
        edges,
        leaves,
        named,
        errors,
        candidateCount,
        generic,
        0,
        nodes + nodes + edges + leaves + named + errors + candidateCount + generic
      )
    )

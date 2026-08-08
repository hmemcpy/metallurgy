package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.{assertEquals, assertSame, assertTrue}
import org.junit.Test

final class SourceEvidencePlannerTest:

  @Test
  def exactEvidenceIsContiguousDeterministicAndCarriesEveryPhysicalClaim(): Unit =
    val source         = "a\r\n\t😀// c\rb\n"
    val commentStart   = source.indexOf("// c")
    val commentEnd     = commentStart + 4
    val nodeOccurrence = ParserNodeOccurrence(
      1,
      Vector(
        ParserFieldPathSegment.NamedField("members"),
        ParserFieldPathSegment.OptionalNesting,
        ParserFieldPathSegment.RepeatedIndex(2),
        ParserFieldPathSegment.NestedProductBoundary("Pair"),
        ParserFieldPathSegment.NamedField("value")
      )
    )
    val snapshot       = fixture(
      source,
      nodes = Vector(
        node(1, 0, source.length, 0),
        node(3, 0, source.length, 0).copy(occurrences = Vector(nodeOccurrence))
      ),
      positioned = Vector(
        ParserPositionedSyntax(
          2,
          "Positioned",
          Vector(ParserSyntaxField("text", ParserFieldValue.Scalar(ParserScalar.Text("neutral")))),
          ParserNodePosition.Positioned(PcSourceRange(0, commentEnd), 0, ParserPositionProvenance.SourceDerived),
          Vector(
            ParserPositionedOccurrence(
              1,
              Vector(
                ParserFieldPathSegment.NamedField("field"),
                ParserFieldPathSegment.RepeatedIndex(0)
              )
            )
          )
        )
      ),
      comments = Vector(ParserComment(PcSourceRange(commentStart, commentEnd), "// c", ParserCommentKind.Line)),
      diagnostics = Vector(
        ParserDiagnostic(
          ParserDiagnosticSeverity.Warning,
          "range",
          Some(ParserDiagnosticPosition(PcSourceRange(0, 1), 1))
        ),
        ParserDiagnostic(ParserDiagnosticSeverity.Information, "absent", None)
      )
    )

    val first  =
      ProvisionalSourceEvidencePlanner
        .plan(snapshot)
        .fold(failures => throw new AssertionError(failures.toString), identity)
    val second =
      ProvisionalSourceEvidencePlanner
        .plan(snapshot)
        .fold(failures => throw new AssertionError(failures.toString), identity)
    assertEquals(first, second)
    assertEquals(source, first.reconstruct(source))
    assertEquals(first.atoms.indices.map(index => SourceAtomId(index.toLong, 0)).toVector, first.atoms.map(_.id))
    assertEquals(0, first.atoms.head.start)
    assertEquals(source.length, first.atoms.last.end)
    assertTrue(first.atoms.sliding(2).forall { case Vector(left, right) => left.end == right.start; case _ => true })
    assertTrue(first.atoms.exists(_.claims.contains(SourceClaim.Node(1, Vector.empty))))
    assertTrue(first.atoms.exists(_.claims.contains(SourceClaim.Node(3, Vector(nodeOccurrence)))))
    assertTrue(first.atoms.exists(_.claims.exists { case SourceClaim.Positioned(2, _) => true; case _ => false }))
    assertTrue(first.atoms.exists(_.claims.contains(SourceClaim.Diagnostic(0))))
    assertTrue(first.atoms.exists(_.comments.nonEmpty))
    assertTrue(
      first.structural.contains(
        StructuralSourceEvidence(
          SourceEvidenceEventId.Diagnostic(1),
          SourceClaim.Diagnostic(1),
          ParserNodePosition.Absent
        )
      )
    )

  @Test
  def syntheticZeroWidthAndAbsentPositionsRemainStructural(): Unit =
    val source   = "x"
    val snapshot = fixture(
      source,
      Vector(
        node(1, 0, 0, 0),
        ParserSyntaxNode(
          2,
          "Synthetic",
          Vector.empty,
          ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.Synthetic),
          Vector.empty
        ),
        ParserSyntaxNode(3, "Absent", Vector.empty, ParserNodePosition.Absent, Vector.empty)
      )
    )
    val plan     =
      ProvisionalSourceEvidencePlanner
        .plan(snapshot)
        .fold(failures => throw new AssertionError(failures.toString), identity)
    assertEquals(3, plan.structural.size)
    assertTrue(plan.atoms.forall(_.claims.isEmpty))

  @Test
  def genericRefinementPreservesClaimsAndReconstructsDelimitersCommentsAndTrailingTrivia(): Unit =
    val source    = "left, /* c */ right\n"
    val comment   = ParserComment(PcSourceRange(6, 13), "/* c */", ParserCommentKind.Block)
    val evidence  = planned(fixture(source, Vector(node(1, 0, source.length, 0)), comments = Vector(comment)))
    val role      = PsiOutputRoleId("test.wrapper")
    val contracts = evidence.atoms.map: original =>
      SourceAtomRefinement(
        SourceAtomReference(original.id, original.start, original.end),
        Vector(role),
        evidence.lexicalContract.atoms
          .filter(atom => original.start <= atom.start && atom.end <= original.end)
          .map(atom => PcSourceRange(atom.start, atom.end))
      )
    val first     = SourceEvidenceRefinementPlanner.refine(evidence, Set(role), contracts).toOption.get
    val second    = SourceEvidenceRefinementPlanner.refine(evidence, Set(role), contracts).toOption.get
    val originals = evidence.atoms.map(atom => atom.id.provisionalId -> atom).toMap

    assertEquals(first, second)
    assertEquals(source, first.reconstruct(source))
    assertEquals(evidence.lexicalContract.atoms.size, first.atoms.size)
    assertTrue(first.atoms.forall(atom => atom.claims == originals(atom.id.provisionalId).claims))
    assertTrue(first.atoms.forall(atom => atom.comments == originals(atom.id.provisionalId).comments))
    assertTrue(first.atoms.exists(_.comments == Vector(comment)))
    assertTrue(first.atoms.sliding(2).forall { case Vector(left, right) => left.end == right.start; case _ => true })

  @Test
  def closedLexicalContractKeepsTokenInteriorsOpaqueAndSeparatesDelimiters(): Unit =
    val source   = "(\"a,b\", `c d`, '\\u0041', 1.25e-2, packageName: ? >: L <: U :: Nil)"
    val contract = ClosedSourceLexicalContract.from(source)
    val atoms    = contract.atoms.map(atom => source.substring(atom.start, atom.end) -> atom.kind)

    assertEquals(source, contract.reconstruct(source))
    assertEquals(
      Vector(
        "("           -> ClosedSourceLexicalKind.LeftParenthesis,
        "\"a,b\""     -> ClosedSourceLexicalKind.Literal,
        ","           -> ClosedSourceLexicalKind.Comma,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "`c d`"       -> ClosedSourceLexicalKind.QuotedIdentifier,
        ","           -> ClosedSourceLexicalKind.Comma,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "'\\u0041'"   -> ClosedSourceLexicalKind.Literal,
        ","           -> ClosedSourceLexicalKind.Comma,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "1.25e-2"     -> ClosedSourceLexicalKind.Number,
        ","           -> ClosedSourceLexicalKind.Comma,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "packageName" -> ClosedSourceLexicalKind.Identifier,
        ":"           -> ClosedSourceLexicalKind.Colon,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "?"           -> ClosedSourceLexicalKind.OperatorIdentifier,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        ">:"          -> ClosedSourceLexicalKind.OperatorIdentifier,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "L"           -> ClosedSourceLexicalKind.Identifier,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "<:"          -> ClosedSourceLexicalKind.OperatorIdentifier,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "U"           -> ClosedSourceLexicalKind.Identifier,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "::"          -> ClosedSourceLexicalKind.OperatorIdentifier,
        " "           -> ClosedSourceLexicalKind.Whitespace,
        "Nil"         -> ClosedSourceLexicalKind.Identifier,
        ")"           -> ClosedSourceLexicalKind.RightParenthesis
      ),
      atoms
    )
    assertTrue(!contract.boundaries(source.indexOf("a,b") + 1))
    assertTrue(!contract.boundaries(source.indexOf("c d") + 1))
    assertTrue(!contract.boundaries(source.indexOf("1.25") + 2))

  @Test
  def refinementRejectsUnknownIdentityRoleUnsafeCutsAndEveryMalformedPartitionAtomically(): Unit =
    val source                                              = "classy"
    val evidence                                            = planned(fixture(source, Vector(node(1, 0, source.length, 0))))
    val atom                                                = evidence.atoms.head
    val reference                                           = SourceAtomReference(atom.id, atom.start, atom.end)
    val role                                                = PsiOutputRoleId("test.terminal")
    def failures(refinements: Vector[SourceAtomRefinement]) =
      SourceEvidenceRefinementPlanner.refine(evidence, Set(role), refinements).left.toOption.get
    def contract(
        replacement: Vector[PcSourceRange],
        atomReference: SourceAtomReference = reference,
        requestingRole: PsiOutputRoleId = role
    ) = SourceAtomRefinement(atomReference, Vector(requestingRole), replacement)

    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 6)), reference.copy(id = SourceAtomId(99, 0)))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.UnknownAtom])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 5)), reference.copy(end = 5))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.AtomRangeChanged])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 6)), requestingRole = PsiOutputRoleId("test.unknown"))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.UnknownRole])
    )
    assertTrue(
      failures(Vector(contract(Vector.empty))).exists(_.isInstanceOf[SourceAtomRefinementFailure.EmptyReplacement])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 0), PcSourceRange(0, 6)))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.EmptyReplacementInterval])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 5)))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.IncompletePartition])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 2), PcSourceRange(3, 6)))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.NonContiguousPartition])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 4), PcSourceRange(2, 6)))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.OverlappingPartition])
    )
    assertTrue(
      failures(Vector(contract(Vector(PcSourceRange(0, 2), PcSourceRange(2, 4), PcSourceRange(4, 6)))))
        .exists(_.isInstanceOf[SourceAtomRefinementFailure.UnsafeBoundary])
    )
    assertTrue(
      failures(
        Vector(
          contract(Vector(PcSourceRange(0, 6))),
          contract(Vector(PcSourceRange(0, 6)), requestingRole = PsiOutputRoleId("test.other"))
        )
      ).exists(_.isInstanceOf[SourceAtomRefinementFailure.OverlappingRefinements])
    )

  @Test
  def finalOwnershipKeepsColocatedEventsDistinctAndRejectsUnknownMultipleAndUnownedEvidence(): Unit =
    val source   = "x"
    val snapshot = fixture(
      source,
      Vector(
        node(1, 0, 1, 0),
        node(2, 0, 0, 0),
        node(3, 0, 0, 0)
      )
    )
    val refined  = SourceEvidenceRefinementPlanner.refine(planned(snapshot), Set.empty, Vector.empty).toOption.get
    val atom     = refined.atoms.head
    val atomRef  = SourceAtomReference(atom.id, atom.start, atom.end)
    val role     = PsiOutputRoleId("test.owner")
    val owner    = SourceEvidenceOwner(role, "owner")
    val events   = refined.structural.map(_.id)
    val atoms    = Vector(SourceAtomOwnership(atomRef, owner))
    val assigned = events.map(SourceEventOwnership(_, owner))
    val first    = FinalSourceEvidencePlanner.plan(refined, Set(role), atoms, assigned).toOption.get
    val second   = FinalSourceEvidencePlanner.plan(refined, Set(role), atoms, assigned).toOption.get

    assertEquals(Vector(SourceEvidenceEventId.Node(2), SourceEvidenceEventId.Node(3)), events)
    assertEquals(first, second)
    assertEquals(source, first.reconstruct(source))
    assertSame(atoms.head, first.atomOwnership.head)
    assertSame(assigned.head, first.eventOwnership.head)

    def failures(
        atomOwnership: Vector[SourceAtomOwnership],
        eventOwnership: Vector[SourceEventOwnership],
        roles: Set[PsiOutputRoleId] = Set(role)
    ) = FinalSourceEvidencePlanner.plan(refined, roles, atomOwnership, eventOwnership).left.toOption.get

    assertTrue(
      failures(atoms, assigned.map(_.copy(owner = owner.copy(role = PsiOutputRoleId("test.unknown")))))
        .exists(_.isInstanceOf[FinalSourceEvidenceFailure.UnknownRole])
    )
    assertTrue(
      failures(atoms :+ SourceAtomOwnership(atomRef.copy(id = SourceAtomId(99, 0)), owner), assigned)
        .exists(_.isInstanceOf[FinalSourceEvidenceFailure.UnknownAtom])
    )
    assertTrue(
      failures(atoms, assigned :+ SourceEventOwnership(SourceEvidenceEventId.Node(99), owner))
        .exists(_.isInstanceOf[FinalSourceEvidenceFailure.UnknownEvent])
    )
    assertTrue(
      failures(atoms :+ atoms.head, assigned).exists(_.isInstanceOf[FinalSourceEvidenceFailure.MultiplyOwnedAtom])
    )
    assertTrue(
      failures(atoms, assigned :+ assigned.head).exists(_.isInstanceOf[FinalSourceEvidenceFailure.MultiplyOwnedEvent])
    )
    assertTrue(failures(Vector.empty, assigned).exists(_.isInstanceOf[FinalSourceEvidenceFailure.UnownedAtom]))
    assertTrue(failures(atoms, assigned.tail).exists(_.isInstanceOf[FinalSourceEvidenceFailure.UnownedEvent]))

    val otherRole    = PsiOutputRoleId("test.other")
    val otherOwner   = SourceEvidenceOwner(otherRole, "other")
    val unknownRole  = PsiOutputRoleId("test.unknown")
    val unknownOwner = SourceEvidenceOwner(unknownRole, "unknown")
    val unknownAtom  = atomRef.copy(id = SourceAtomId(99, 0))
    val unknownEvent = SourceEvidenceEventId.Node(99)
    assertEquals(
      Vector(
        FinalSourceEvidenceFailure.UnknownRole(unknownRole),
        FinalSourceEvidenceFailure.UnknownAtom(unknownAtom),
        FinalSourceEvidenceFailure.MultiplyOwnedAtom(atomRef, Vector(owner, otherOwner)),
        FinalSourceEvidenceFailure.UnknownEvent(unknownEvent),
        FinalSourceEvidenceFailure.MultiplyOwnedEvent(events.head, Vector(owner, otherOwner)),
        FinalSourceEvidenceFailure.UnownedEvent(events(1))
      ),
      failures(
        atoms ++ Vector(SourceAtomOwnership(unknownAtom, unknownOwner), SourceAtomOwnership(atomRef, otherOwner)),
        Vector(assigned.head, SourceEventOwnership(events.head, otherOwner), SourceEventOwnership(unknownEvent, owner)),
        Set(role, otherRole)
      )
    )

  @Test def finalOwnershipWorkScalesLinearlyAndPreservesCanonicalEvidenceOrder(): Unit =
    val sizes = Vector(64, 128, 256, 512, 1024)
    val work  = sizes.map: size =>
      val source   = "x" * size
      val nodes    = Vector.tabulate(size)(index => node(index + 1L, index, index + 1, index)) ++
        Vector.tabulate(size)(index => node(size + index + 1L, index, index, index))
      val refined  = SourceEvidenceRefinementPlanner
        .refine(planned(fixture(source, nodes)), Set.empty, Vector.empty)
        .toOption
        .get
      val role     = PsiOutputRoleId("test.scale.owner")
      val atomRefs = refined.atoms.map(atom => SourceAtomReference(atom.id, atom.start, atom.end))
      val atoms    = atomRefs.zipWithIndex.map: (atom, index) =>
        SourceAtomOwnership(atom, SourceEvidenceOwner(role, s"atom-$index"))
      val events   = refined.structural.zipWithIndex.map: (event, index) =>
        SourceEventOwnership(event.id, SourceEvidenceOwner(role, s"event-$index"))
      val observer = new CountingPlanningWorkObserver
      val result   = FinalSourceEvidencePlanner
        .plan(refined, Set(role), atoms.reverse, events.reverse, observer)
        .toOption
        .get

      assertEquals(source, result.reconstruct(source))
      assertEquals(atomRefs, result.atomOwnership.map(_.atom))
      assertEquals(refined.structural.map(_.id), result.eventOwnership.map(_.eventId))
      Vector(0, size / 2, size - 1).foreach: index =>
        assertEquals(s"atom-$index", result.atomOwnership(index).owner.identity)
        assertEquals(s"event-$index", result.eventOwnership(index).owner.identity)
      assertTrue(s"size=$size work=${observer.finalOwnership}", observer.finalOwnership <= 32L * size + 128L)
      observer.finalOwnership

    work
      .sliding(2)
      .foreach:
        case Vector(previous, current) =>
          assertTrue(s"previous=$previous current=$current", current <= 3L * previous + 256L)
        case _                         => ()

  @Test
  def malformedSnapshotsReturnStructuredFailures(): Unit =
    val source    = "/*x*/"
    val malformed = fixture(
      source,
      nodes = Vector(node(1, 0, source.length, 0), node(1, 0, source.length + 1, source.length + 2)),
      comments = Vector(
        ParserComment(PcSourceRange(0, 5), "wrong", ParserCommentKind.Block),
        ParserComment(PcSourceRange(4, 5), "/", ParserCommentKind.Block)
      ),
      diagnostics = Vector(
        ParserDiagnostic(ParserDiagnosticSeverity.Error, "bad", Some(ParserDiagnosticPosition(PcSourceRange(0, 1), 2)))
      )
    ).copy(sourceLength = 1, sourceDigest = "bad")
    val failures  = ProvisionalSourceEvidencePlanner.plan(malformed).swap.getOrElse(Vector.empty)
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.SourceLengthMismatch]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.DigestMismatch]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.DuplicateIdentity]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.InvalidRange]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.InvalidPoint]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.CommentMismatch]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.OverlappingComments]))

    def markerFailures(snapshot: ParserSyntaxSnapshot): Vector[SourceEvidenceFailure] =
      ProvisionalSourceEvidencePlanner.plan(snapshot).swap.getOrElse(Vector.empty)
    val markerBase                                                                    = fixture(source, Vector(node(1, 0, source.length, 0)))
    assertTrue(
      markerFailures(
        markerBase.copy(endMarkers = Vector.fill(2)(ParserEndMarker(1, PcSourceRange(1, 2))))
      ).exists(_.isInstanceOf[SourceEvidenceFailure.DuplicateEndMarkerOwner])
    )
    assertTrue(
      markerFailures(markerBase.copy(endMarkers = Vector(ParserEndMarker(2, PcSourceRange(1, 2)))))
        .exists(_.isInstanceOf[SourceEvidenceFailure.UnknownEndMarkerOwner])
    )
    assertTrue(
      markerFailures(markerBase.copy(endMarkers = Vector(ParserEndMarker(1, PcSourceRange(2, 2)))))
        .exists(_.isInstanceOf[SourceEvidenceFailure.InvalidEndMarkerRange])
    )
    assertTrue(
      markerFailures(
        markerBase.copy(
          nodes = Vector(node(1, 0, 1, 0)),
          endMarkers = Vector(ParserEndMarker(1, PcSourceRange(2, 3)))
        )
      ).exists(_.isInstanceOf[SourceEvidenceFailure.EndMarkerOutsideOwner])
    )

  private final class CountingPlanningWorkObserver extends PlanningWorkObserver:
    var finalOwnership: Long = 0L

    override def finalOwnershipEntries(count: Int): Unit    = finalOwnership += count
    override def terminalLexicalEntries(count: Int): Unit   = ()
    override def terminalCandidateEntries(count: Int): Unit = ()

  private def planned(snapshot: ParserSyntaxSnapshot): ProvisionalSourceEvidencePlan =
    ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(failures => throw new AssertionError(failures.toString), identity)

  private def node(id: Long, start: Int, end: Int, point: Int): ParserSyntaxNode =
    ParserSyntaxNode(
      id,
      "Node",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(start, end), point, ParserPositionProvenance.SourceDerived),
      Vector.empty
    )

  private def fixture(
      source: String,
      nodes: Vector[ParserSyntaxNode],
      positioned: Vector[ParserPositionedSyntax] = Vector.empty,
      comments: Vector[ParserComment] = Vector.empty,
      diagnostics: Vector[ParserDiagnostic] = Vector.empty
  ): ParserSyntaxSnapshot =
    ParserSyntaxSnapshot(
      ParserSourceUri.from("file:///Evidence.scala").fold(message => throw new AssertionError(message), identity),
      source,
      ParserSyntaxSnapshot.digest(source),
      source.length,
      Vector("-source", "future"),
      nodes.head.id,
      nodes,
      positioned,
      comments,
      diagnostics,
      Scala3ParserCapabilities(
        ParserCapabilityStatus.Unavailable("not published"),
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available
      ),
      Scala3ParserCompilerIdentity(
        Scala3ParserArtifactCoordinate("org", "compiler", "test"),
        Vector.empty,
        Scala3ParserLoaderIdentity(1)
      ),
      Vector.empty
    )

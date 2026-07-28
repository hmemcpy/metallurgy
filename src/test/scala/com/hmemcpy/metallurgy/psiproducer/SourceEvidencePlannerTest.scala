package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

final class SourceEvidencePlannerTest:

  @Test
  def exactEvidenceIsContiguousDeterministicAndCarriesEveryPhysicalClaim(): Unit =
    val source       = "a\r\n\t😀// c\rb\n"
    val commentStart = source.indexOf("// c")
    val commentEnd   = commentStart + 4
    val snapshot     = fixture(
      source,
      nodes = Vector(node(1, 0, source.length, 0)),
      positioned = Vector(
        ParserPositionedSyntax(
          2,
          "Positioned",
          Vector(ParserSyntaxField("text", ParserFieldValue.Scalar(ParserScalar.Text("neutral")))),
          ParserNodePosition.Positioned(PcSourceRange(0, commentEnd), 0, ParserPositionProvenance.SourceDerived),
          Vector(ParserPositionedOccurrence(1, Vector("field", "0")))
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
      SourceEvidencePlanner.plan(snapshot).fold(failures => throw new AssertionError(failures.toString), identity)
    val second =
      SourceEvidencePlanner.plan(snapshot).fold(failures => throw new AssertionError(failures.toString), identity)
    assertEquals(first, second)
    assertEquals(source, first.reconstruct(source))
    assertEquals(first.atoms.indices.map(_.toLong).toVector, first.atoms.map(_.id))
    assertEquals(0, first.atoms.head.start)
    assertEquals(source.length, first.atoms.last.end)
    assertTrue(first.atoms.sliding(2).forall { case Vector(left, right) => left.end == right.start; case _ => true })
    assertTrue(first.atoms.exists(_.claims.contains(SourceClaim.Node(1))))
    assertTrue(first.atoms.exists(_.claims.exists { case SourceClaim.Positioned(2, _) => true; case _ => false }))
    assertTrue(first.atoms.exists(_.claims.contains(SourceClaim.Diagnostic(0))))
    assertTrue(first.atoms.exists(_.comments.nonEmpty))
    assertTrue(
      first.structural.contains(StructuralSourceEvidence(SourceClaim.Diagnostic(1), ParserNodePosition.Absent))
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
          ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.Synthetic)
        ),
        ParserSyntaxNode(3, "Absent", Vector.empty, ParserNodePosition.Absent)
      )
    )
    val plan     =
      SourceEvidencePlanner.plan(snapshot).fold(failures => throw new AssertionError(failures.toString), identity)
    assertEquals(3, plan.structural.size)
    assertTrue(plan.atoms.forall(_.claims.isEmpty))

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
    val failures  = SourceEvidencePlanner.plan(malformed).swap.getOrElse(Vector.empty)
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.SourceLengthMismatch]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.DigestMismatch]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.DuplicateIdentity]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.InvalidRange]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.InvalidPoint]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.CommentMismatch]))
    assertTrue(failures.exists(_.isInstanceOf[SourceEvidenceFailure.OverlappingComments]))

  private def node(id: Long, start: Int, end: Int, point: Int): ParserSyntaxNode =
    ParserSyntaxNode(
      id,
      "Node",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(start, end), point, ParserPositionProvenance.SourceDerived)
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
        ParserCapabilityStatus.Available
      ),
      Scala3ParserCompilerIdentity(
        Scala3ParserArtifactCoordinate("org", "compiler", "test"),
        Vector.empty,
        Scala3ParserLoaderIdentity(1)
      )
    )

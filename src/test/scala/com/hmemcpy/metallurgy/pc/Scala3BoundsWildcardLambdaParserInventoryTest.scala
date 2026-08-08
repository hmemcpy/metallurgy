package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.ProvisionalSourceEvidencePlanner
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3BoundsWildcardLambdaParserInventoryTest:

  @Test
  def exactBoundsWildcardsContextBoundsAndLambdasAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val first              = parse(bridge, Source, "file:///P123DBoundsFirst.scala")
      val second             = parse(bridge, Source, "file:///P123DBoundsSecond.scala")
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(Source, first.sourceText)
      assertEquals(Source, evidence(first).reconstruct(Source))
      assertTrue(first.diagnostics.toString, first.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertTrue(first.nodes.exists(_.production == "TypeBoundsTree"))
      assertTrue(first.nodes.exists(_.production == "ContextBounds"))
      assertTrue(first.nodes.exists(_.production == "ContextBoundTypeTree"))
      assertTrue(first.nodes.exists(_.production == "LambdaTypeTree"))
      assertTrue(first.toString, !"Name\\(_\\$[0-9]+\\)".r.findFirstIn(first.toString).isDefined)
      assertTrue(first.nodes.exists(_.fields.exists(_.value == ParserFieldValue.Name("High"))))
      val bounds             = first.nodes.filter(_.production == "TypeBoundsTree")
      assertTrue(bounds.nonEmpty)
      assertTrue(bounds.forall(_.fields.map(_.name) == Vector("lo", "hi", "alias")))
      val generatedWildcards = first.nodes.filter: node =>
        node.production == "TypeDef" && node.fields.exists:
          _.value match
            case ParserFieldValue.GeneratedName("", "_$", _) => true
            case _                                           => false
      assertTrue(generatedWildcards.nonEmpty)
      assertTrue(generatedWildcards.forall: node =>
        node.position match
          case ParserNodePosition.Positioned(_, point, ParserPositionProvenance.SourceDerived) =>
            Source.substring(point, point + 1) == "_"
          case _                                                                               => false
      )
      assertEquals(
        generatedWildcards.size,
        generatedWildcards
          .flatMap(_.fields.collect {
            case ParserSyntaxField("name", ParserFieldValue.GeneratedName("", "_$", index), _) => index
          })
          .distinct
          .size
      )
      assertTrue(generatedWildcards.forall(modifierFlags(_) == 259L))
      assertTrue(first.nodes.exists(node => modifierFlags(node) == 1057030L))
      assertTrue(first.nodes.exists(node => modifierFlags(node) == 2105606L))
      val byId               = first.nodes.map(node => node.id -> node).toMap
      first.nodes
        .filter(_.production == "ContextBoundTypeTree")
        .foreach: contextBound =>
          val tyconId  = contextBound.fields.collectFirst {
            case ParserSyntaxField("tycon", ParserFieldValue.Node(id), _) => id
          }.get
          val tyconEnd = byId(tyconId).position match
            case ParserNodePosition.Positioned(range, _, _) => range.endOffset
            case other                                      => throw new AssertionError(other.toString)
          contextBound.position match
            case ParserNodePosition.Positioned(_, point, ParserPositionProvenance.SourceDerived) =>
              assertEquals(tyconEnd, point)
            case other                                                                           => throw new AssertionError(other.toString)
      assertTrue(first.nodes.exists(_.fields.exists(_.value == ParserFieldValue.Name("evidence"))))
      val tokenTexts         =
        first.scannerTokens.map(token => Source.substring(token.range.startOffset, token.range.endOffset))
      Vector("?", "_", ">:", "<:", ":", "as", "[", "]", "=>>").foreach(token =>
        assertTrue(s"missing token $token in $tokenTexts", tokenTexts.contains(token))
      )
      println(s"compilerIdentity=${first.compilerIdentity}")
      println(s"sourceDigest=${first.sourceDigest}")
      println(s"fingerprint=${ParserSyntaxSnapshot.evidenceFingerprint(first)}")
      println(s"diagnostics=${first.diagnostics}")
      println(s"reconstruction=${evidence(first).reconstruct(Source) == Source}")
      first.nodes
        .filter(node =>
          Set(
            "TypeDef",
            "DefDef",
            "ValDef",
            "Template",
            "TypeBoundsTree",
            "ContextBounds",
            "ContextBoundTypeTree",
            "LambdaTypeTree",
            "AppliedTypeTree"
          )(node.production)
        )
        .foreach(node => println(render(first, node)))
      println(s"generatedWildcards=$generatedWildcards")
      println(s"attachments=${first.attachments}")
      println(
        s"tokens=${first.scannerTokens.map(token => Source.substring(token.range.startOffset, token.range.endOffset))}"
      )
    finally bridge.close()

  @Test
  def typeDefinitionTermLambdaAndCaptureAttachmentRemainExactNeutralEvidence(): Unit =
    val bridge = openBridge()
    try
      val term       = parse(bridge, TermLambdaSource, "file:///P123DTermLambda.scala")
      val termLambda = term.nodes.find(_.production == "TermLambdaTypeTree").get
      assertEquals(Vector("params", "body"), termLambda.fields.map(_.name))
      termLambda.position match
        case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.Synthetic) => ()
        case other                                                                   => throw new AssertionError(other.toString)
      assertEquals(TermLambdaSource, evidence(term).reconstruct(TermLambdaSource))
      println(s"termFingerprint=${ParserSyntaxSnapshot.evidenceFingerprint(term)}")
      term.nodes.filter(_.production == "TermLambdaTypeTree").foreach(node => println(render(term, node)))

      val capture = parse(
        bridge,
        CaptureSource,
        "file:///P123DCaptureAttachment.scala",
        Vector("-language:experimental.captureChecking")
      )
      assertEquals(CaptureSource, evidence(capture).reconstruct(CaptureSource))
      assertEquals(2, capture.attachments.size)
      assertTrue(capture.attachments.forall(_.keyKind == "CaptureVar"))
      assertTrue(capture.attachments.forall(_.value == ParserAttachmentValue.RuntimeKind("BoxedUnit")))
      println(s"captureFingerprint=${ParserSyntaxSnapshot.evidenceFingerprint(capture)}")
      println(s"captureAttachments=${capture.attachments}")
    finally bridge.close()

  private def render(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String =
    s"${node.id}:${node.production}:${node.position}:${text(snapshot, node)}:${node.fields}:${node.occurrences}"

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case _                                          => "<synthetic>"

  private def modifierFlags(node: ParserSyntaxNode): Long = node.fields
    .collectFirst:
      case ParserSyntaxField(
            "mods",
            ParserFieldValue.Product(
              "Modifiers",
              ParserSyntaxField("flags", ParserFieldValue.Scalar(ParserScalar.LongInteger(flags)), _) +: _
            ),
            _
          ) =>
        flags
    .getOrElse(Long.MinValue)

  private def evidence(snapshot: ParserSyntaxSnapshot) =
    ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)

  private def parse(
      bridge: Scala3ParserBridge,
      source: String,
      uri: String,
      options: Vector[String] = Vector.empty
  ): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri.from(uri).fold(sys.error, identity),
          source,
          options,
          Scala3ParserCancellation.Never
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def openBridge(): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion),
        compilerDistribution.map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def compilerDistribution: Seq[Path] =
    Scala3CompilerResolver.publicCoursier.resolve(ScalaVersion).fold(error => throw error.toException, identity)

  private val ScalaVersion     = "3.7.4"
  private val Source           =
    """trait High
      |trait Low extends High
      |trait Evidence[A]
      |trait BinaryEvidence[A, B]
      |trait KindEvidence[F[_]]
      |trait UsesColl[Coll[_]]
      |trait Higher[F[_], G[X >: Low <: High], H[_[_]]]
      |trait Variance[+A >: Low <: High, -B]
      |class ClassOwner[-A >: Low <: High, F[_]]
      |enum EnumOwner[A >: Low <: High, F[_]]:
      |  case One[A >: Low <: High, F[_]](value: F[A]) extends EnumOwner[A, F]
      |def functionOwner[A >: Low <: High, F[_]]: A = ???
      |type Upper[A <: High] = A
      |type Lower[A >: Low] = A
      |type Both[A >: Low <: High] = A
      |type WildUpper[T] = List[? <: T]
      |type WildLegacy[T] = List[_ >: Low <: T]
      |type WildBoth = List[? >: Low <: High]
      |type TypeLambda = [X >: Low <: High] =>> List[X]
      |type HigherLambda = [F[_]] =>> F[High]
      |opaque type Hidden = High
      |opaque type AliasBounds >: Low <: High = High
      |def unnamed[A: Evidence](value: A): A = value
      |def named[A: Evidence as evidence](value: A): Evidence[A] = evidence
      |def aggregate[A: {Evidence, [X] =>> BinaryEvidence[X, X]}](value: A): A = value
      |def higher[F[_]: KindEvidence](value: F[High]): F[High] = value
      |""".stripMargin
  private val TermLambdaSource =
    """import scala.language.experimental.modularity
      |
      |type Vec[T](n: Int) = Array[T]
      |""".stripMargin
  private val CaptureSource    = "trait Captured[A^]\n"

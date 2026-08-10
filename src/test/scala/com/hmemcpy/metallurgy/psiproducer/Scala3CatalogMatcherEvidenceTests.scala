package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait Scala3CatalogMatcherEvidenceTests extends Scala3PsiProductionCatalogTestSupport:
  @Test def repeatedAncestorContextMatchesOnlyAContiguousLineageEndingAtItsAnchor(): Unit =
    val qualifier                                     = Vector(CatalogPathSegment.NamedField("qualifier"))
    val repeated                                      = InventoryAncestor(InventoryKind.Node, "Select", qualifier)
    val anchor                                        = InventoryAncestor(
      InventoryKind.Node,
      "Import",
      Vector(CatalogPathSegment.NamedField("expr"))
    )
    val pattern                                       = ContextPattern.ParentWithRepeatedAncestor(
      InventoryKind.Node,
      "Select",
      qualifier,
      repeated,
      anchor
    )
    val anchored                                      = ContextPattern.AnchorOrParentWithRepeatedAncestor(
      anchor,
      InventoryKind.Node,
      "Select",
      qualifier,
      repeated
    )
    val adjacent                                      = InventoryAncestor(InventoryKind.Node, "Apply", qualifier)
    val sequence                                      = ContextPattern.ParentWithRepeatedAncestorSequencePrefix(
      InventoryKind.Node,
      "Select",
      qualifier,
      Vector(repeated, adjacent),
      Vector(anchor)
    )
    def context(ancestors: Vector[InventoryAncestor]) =
      Some(InventoryContext(InventoryKind.Node, "Select", qualifier, ancestors))
    val direct                                        = Some(InventoryContext(anchor.ownerKind, anchor.ownerPrefix, anchor.path))

    Vector(
      context(Vector(anchor)),
      context(Vector(repeated, anchor)),
      context(Vector.fill(32)(repeated) :+ anchor)
    ).foreach: candidate =>
      assertTrue(CatalogShapeMatcher.contextMatches(pattern, candidate))
      assertTrue(CatalogShapeMatcher.aggregateContextMatches(pattern, candidate))
      assertTrue(CatalogShapeMatcher.contextMatches(anchored, candidate))
      assertTrue(CatalogShapeMatcher.aggregateContextMatches(anchored, candidate))
    assertTrue(CatalogShapeMatcher.contextMatches(anchored, direct))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(anchored, direct))
    Vector(
      context(Vector(anchor)),
      context(Vector(repeated, adjacent, anchor)),
      context(Vector.fill(16)(Vector(repeated, adjacent)).flatten :+ anchor)
    ).foreach: candidate =>
      assertTrue(CatalogShapeMatcher.contextMatches(sequence, candidate))
      assertTrue(CatalogShapeMatcher.aggregateContextMatches(sequence, candidate))

    Vector(
      None,
      context(Vector.empty),
      context(Vector.fill(32)(repeated)),
      context(Vector(repeated, adjacent, anchor)),
      Some(
        InventoryContext(InventoryKind.Node, "Select", Vector(CatalogPathSegment.NamedField("other")), Vector(anchor))
      )
    ).foreach: candidate =>
      assertFalse(CatalogShapeMatcher.contextMatches(pattern, candidate))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, candidate))
      assertFalse(CatalogShapeMatcher.contextMatches(anchored, candidate))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(anchored, candidate))
    Vector(
      None,
      context(Vector.empty),
      context(Vector.fill(16)(Vector(repeated, adjacent)).flatten),
      context(Vector(adjacent, repeated, anchor)),
      context(Vector(repeated, adjacent, repeated, anchor))
    ).foreach: candidate =>
      assertFalse(CatalogShapeMatcher.contextMatches(sequence, candidate))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(sequence, candidate))

  @Test def sourceOrderedTerminalRangeWorkRetainsExactSelectionsWithNLogNGrowth(): Unit =
    val sizes = Vector(32, 64, 128, 256, 512)
    val work  = sizes.map: size =>
      val tokens         = Vector.tabulate(size)(index => f"t$index%04d")
      val source         = tokens.mkString(" ")
      val lexical        = ClosedSourceLexicalContract.from(source)
      val observer       = new CountingPlanningWorkObserver
      val lexicalIndex   = new SourceOrderedRangeIndex(
        lexical.atoms,
        _.start,
        _.end,
        observer.terminalLexicalEntries
      )
      val candidateIndex = new SourceOrderedRangeIndex(
        lexical.atoms,
        _.start,
        _.end,
        observer.terminalCandidateEntries
      )
      val tokenAtoms     = lexical.atoms.filter(_.kind == ClosedSourceLexicalKind.Identifier)
      val selected       = tokenAtoms.zipWithIndex.map: (token, index) =>
        val range      = PcSourceRange(token.start, token.end)
        val lexicalAt  = lexicalIndex.within(range)
        val candidates = candidateIndex.within(range)
        assertEquals(Vector(token), lexicalAt)
        assertEquals(Vector(token), candidates)
        assertEquals(tokens(index), source.substring(token.start, token.end))
        token

      assertEquals(tokenAtoms, selected)
      assertEquals(selected.size, selected.distinct.size)
      assertEquals(
        source,
        lexicalIndex
          .within(PcSourceRange(0, source.length))
          .map(atom => source.substring(atom.start, atom.end))
          .mkString
      )
      Vector(0, size / 2, size - 1).foreach: index =>
        val atom = selected(index)
        assertEquals(tokens(index), source.substring(atom.start, atom.end))
      val logarithmicCeiling = 32 - Integer.numberOfLeadingZeros(lexical.atoms.size - 1)
      val envelope           = 8L * size * (logarithmicCeiling + 2L) + 128L
      assertTrue(
        s"size=$size lexical=${observer.terminalLexical} candidates=${observer.terminalCandidates} envelope=$envelope",
        observer.terminalLexical + observer.terminalCandidates <= envelope
      )
      observer.terminalLexical + observer.terminalCandidates

    work
      .sliding(2)
      .foreach:
        case Vector(previous, current) =>
          assertTrue(s"previous=$previous current=$current", current <= 3L * previous + 256L)
        case _                         => ()

  private final class CountingPlanningWorkObserver extends PlanningWorkObserver:
    var terminalLexical: Long    = 0L
    var terminalCandidates: Long = 0L

    override def finalOwnershipEntries(count: Int): Unit    = ()
    override def terminalLexicalEntries(count: Int): Unit   = terminalLexical += count
    override def terminalCandidateEntries(count: Int): Unit = terminalCandidates += count

  @Test def boundedTypeParameterSelectionAcceptsNestedLambdaLineageAndRejectsOtherParents(): Unit =
    val lambdaParameter                                                = InventoryAncestor(
      InventoryKind.Node,
      "LambdaTypeTree",
      Vector(CatalogPathSegment.NamedField("tparams"), CatalogPathSegment.RepeatedElement)
    )
    val lambdaBody                                                     = InventoryAncestor(
      InventoryKind.Node,
      "LambdaTypeTree",
      Vector(CatalogPathSegment.NamedField("body"))
    )
    val outerAlias                                                     = InventoryAncestor(
      InventoryKind.Node,
      "TypeDef",
      Vector(CatalogPathSegment.NamedField("rhs"))
    )
    val fields                                                         = Vector(
      InventoryFieldObservation("lo", InventoryValueObservation.Node(1L, "Ident")),
      InventoryFieldObservation("hi", InventoryValueObservation.Node(2L, "Ident")),
      InventoryFieldObservation("alias", InventoryValueObservation.Node(3L, "Thicket"))
    )
    def selected(ancestors: Vector[InventoryAncestor]): Vector[String] = CatalogShapeMatcher
      .select(
        Scala3PsiProductionCatalog.Reviewed,
        InventoryKind.Node,
        "TypeBoundsTree",
        fields,
        Some(
          InventoryContext(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            ancestors
          )
        ),
        SourceClassification.SourceReachable
      )
      .map(_.id)

    assertEquals(
      Vector("type-parameter-bounds"),
      selected(lambdaParameter +: Vector.fill(16)(lambdaBody) :+ outerAlias)
    )
    assertTrue(selected(lambdaBody +: Vector.fill(16)(lambdaBody) :+ outerAlias).isEmpty)

  @Test def parentContextMatchesMixedTypeLineageOnlyUnderItsSelectorAnchor(): Unit =
    val anchor     = InventoryAncestor(
      InventoryKind.Node,
      "ImportSelector",
      Vector(CatalogPathSegment.NamedField("bound"))
    )
    val applied    = InventoryAncestor(
      InventoryKind.Node,
      "AppliedTypeTree",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )
    val infix      = InventoryAncestor(
      InventoryKind.Node,
      "InfixOp",
      Vector(CatalogPathSegment.NamedField("right"))
    )
    val descendant = Some(
      InventoryContext(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField("hi")),
        Vector(infix, applied, anchor)
      )
    )
    val deep       = Some(
      InventoryContext(
        InventoryKind.Node,
        "InfixOp",
        Vector(CatalogPathSegment.NamedField("right")),
        Vector.fill(10000)(infix) :+ applied :+ anchor
      )
    )
    val parent     = ContextPattern.ParentUnderAnchor(
      InventoryKind.Node,
      "TypeBoundsTree",
      Vector(CatalogPathSegment.NamedField("hi")),
      anchor
    )
    assertTrue(CatalogShapeMatcher.contextMatches(parent, descendant))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(parent, descendant))
    val deepParent = ContextPattern.ParentUnderAnchor(
      InventoryKind.Node,
      "InfixOp",
      Vector(CatalogPathSegment.NamedField("right")),
      anchor
    )
    assertTrue(CatalogShapeMatcher.contextMatches(deepParent, deep))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(deepParent, deep))
    Vector(
      None,
      Some(
        InventoryContext(
          InventoryKind.Node,
          "TypeBoundsTree",
          Vector(CatalogPathSegment.NamedField("hi")),
          Vector(infix, applied)
        )
      ),
      Some(
        InventoryContext(
          InventoryKind.Node,
          "TypeBoundsTree",
          Vector(CatalogPathSegment.NamedField("lo")),
          Vector(infix, applied, anchor)
        )
      )
    ).foreach: context =>
      assertFalse(CatalogShapeMatcher.contextMatches(parent, context))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(parent, context))

    val boundsFields                                        = Vector(
      InventoryFieldObservation("lo", InventoryValueObservation.Node(1L, "Thicket")),
      InventoryFieldObservation("hi", InventoryValueObservation.Node(2L, "Ident")),
      InventoryFieldObservation("alias", InventoryValueObservation.Node(1L, "Thicket"))
    )
    def selected(context: InventoryContext): Vector[String] = CatalogShapeMatcher
      .select(
        Scala3PsiProductionCatalog.Reviewed,
        InventoryKind.Node,
        "TypeBoundsTree",
        boundsFields,
        Some(context),
        SourceClassification.SourceReachable
      )
      .map(_.id)
    assertEquals(
      Vector("import-selector-given-bound-wildcard-type"),
      selected(
        InventoryContext(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
          Vector(anchor)
        )
      )
    )
    Vector(
      InventoryContext(InventoryKind.Node, "ImportSelector", Vector(CatalogPathSegment.NamedField("bound"))),
      InventoryContext(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      ),
      InventoryContext(
        InventoryKind.Node,
        "InfixOp",
        Vector(CatalogPathSegment.NamedField("right")),
        Vector(anchor)
      )
    ).foreach(context => assertTrue(selected(context).isEmpty))

  @Test def parentContextThroughTypeAncestorsRejectsAnInterveningExpressionOwner(): Unit =
    val path                                          = Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)
    val anchor                                        = InventoryAncestor(
      InventoryKind.Node,
      "TypeDef",
      Vector(CatalogPathSegment.NamedField("rhs"))
    )
    val applied                                       = InventoryAncestor(
      InventoryKind.Node,
      "AppliedTypeTree",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )
    val function                                      = InventoryAncestor(
      InventoryKind.Node,
      "Function",
      Vector(CatalogPathSegment.NamedField("body"))
    )
    val expression                                    = InventoryAncestor(
      InventoryKind.Node,
      "DefDef",
      Vector(CatalogPathSegment.NamedField("preRhs"))
    )
    val pattern                                       = ContextPattern.ParentUnderAnchorThrough(
      InventoryKind.Node,
      "Tuple",
      path,
      Vector(applied, function),
      anchor
    )
    def context(ancestors: Vector[InventoryAncestor]) =
      Some(InventoryContext(InventoryKind.Node, "Tuple", path, ancestors))

    Vector(Vector(anchor), Vector(applied, anchor), Vector(function, applied, anchor)).foreach: ancestors =>
      assertTrue(CatalogShapeMatcher.contextMatches(pattern, context(ancestors)))
      assertTrue(CatalogShapeMatcher.aggregateContextMatches(pattern, context(ancestors)))
    Vector(Vector(expression, anchor), Vector(applied, expression, anchor)).foreach: ancestors =>
      assertFalse(CatalogShapeMatcher.contextMatches(pattern, context(ancestors)))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, context(ancestors)))

  @Test def expressionInfixOperatorUnderATypeOwnedTemplateRemainsExpressionOnly(): Unit =
    val context  = InventoryContext(
      InventoryKind.Node,
      "InfixOp",
      Vector(CatalogPathSegment.NamedField("op")),
      Vector(
        InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("preRhs"))),
        InventoryAncestor(
          InventoryKind.Node,
          "Template",
          Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
        ),
        InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs")))
      )
    )
    val selected = CatalogShapeMatcher.select(
      Scala3PsiProductionCatalog.Reviewed,
      InventoryKind.Node,
      "Ident",
      Vector(InventoryFieldObservation("name", InventoryValueObservation.Name("combine"))),
      Some(context),
      SourceClassification.SourceReachable
    )
    assertEquals(Vector("payload-descendant-ident"), selected.map(_.id))

  @Test def typeAtomSelectionRequiresExactScannerEvidenceAndContext(): Unit =
    val catalog      = Scala3PsiProductionCatalog.Reviewed
    val direct       = Some(
      InventoryContext(
        InventoryKind.Node,
        "ImportSelector",
        Vector(CatalogPathSegment.NamedField("bound"))
      )
    )
    val selectFields = Vector(
      InventoryFieldObservation("qualifier", InventoryValueObservation.Node(1L, "Ident")),
      InventoryFieldObservation("name", InventoryValueObservation.Name("A"))
    )
    def selected(
        prefix: String,
        fields: Vector[InventoryFieldObservation],
        scanner: Vector[ParserScannerTokenKind],
        classification: SourceClassification = SourceClassification.SourceReachable
    ): Vector[String] =
      CatalogShapeMatcher
        .select(
          catalog,
          InventoryKind.Node,
          prefix,
          fields,
          direct,
          classification,
          scanner
        )
        .map(_.id)

    assertEquals(
      Vector("import-selector-given-bound-qualified-type"),
      selected("Select", selectFields, Vector(ParserScannerTokenKind.Dot))
    )
    assertEquals(
      Vector("type-atom-projection"),
      selected("Select", selectFields, Vector(ParserScannerTokenKind.Hash))
    )
    assertTrue(selected("Select", selectFields, Vector.empty).isEmpty)
    assertEquals(
      Vector("type-atom-projection"),
      selected("Select", selectFields, Vector(ParserScannerTokenKind.Dot, ParserScannerTokenKind.Hash))
    )

    val singletonFields = Vector(
      InventoryFieldObservation("ref", InventoryValueObservation.Node(2L, "Ident"))
    )
    assertEquals(
      Vector("type-atom-singleton-ident"),
      selected(
        "SingletonTypeTree",
        singletonFields,
        Vector(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword)
      )
    )
    assertTrue(
      selected("SingletonTypeTree", singletonFields, Vector(ParserScannerTokenKind.Dot)).isEmpty
    )
    assertEquals(
      Vector("type-atom-singleton-ident"),
      selected(
        "SingletonTypeTree",
        singletonFields,
        Vector(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword),
        SourceClassification.Synthetic
      )
    )
    val literalFields   = Vector(
      InventoryFieldObservation("ref", InventoryValueObservation.Node(3L, "Literal"))
    )
    assertEquals(
      Vector("type-atom-literal"),
      selected(
        "SingletonTypeTree",
        literalFields,
        Vector(ParserScannerTokenKind.Literal),
        SourceClassification.Synthetic
      )
    )
    assertTrue(selected("SingletonTypeTree", literalFields, Vector(ParserScannerTokenKind.Literal)).isEmpty)
    assertTrue(selected("SingletonTypeTree", literalFields, Vector.empty).isEmpty)
    val parensFields    = Vector(
      InventoryFieldObservation("t", InventoryValueObservation.Node(4L, "Ident"))
    )
    assertEquals(
      Vector("type-atom-parenthesized"),
      selected(
        "Parens",
        parensFields,
        Vector(ParserScannerTokenKind.LeftParenthesis, ParserScannerTokenKind.RightParenthesis)
      )
    )
    assertTrue(
      selected("Parens", parensFields, Vector(ParserScannerTokenKind.LeftParenthesis)).isEmpty
    )

  @Test def matcherDistinguishesNodesFromScalarsAndChecksNestedFields(): Unit =
    assertFalse(
      CatalogShapeMatcher.matches(
        CatalogValuePattern.Node,
        InventoryValueObservation.Scalar(ParserScalar.Logical(true))
      )
    )
    val observed      = InventoryValueObservation.Product(
      "Pair",
      Vector(InventoryFieldObservation("actual", InventoryValueObservation.Name("x")))
    )
    assertFalse(
      CatalogShapeMatcher.matches(
        CatalogValuePattern.Product(
          "Pair",
          Vector(CompilerFieldPattern("expected", CatalogValuePattern.Name))
        ),
        observed
      )
    )
    val scalar        = InventoryValueObservation.Scalar(ParserScalar.LongInteger(1026L))
    assertTrue(CatalogShapeMatcher.matches(CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(1026)"), scalar))
    assertFalse(CatalogShapeMatcher.matches(CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(0)"), scalar))
    assertTrue(
      CatalogShapeMatcher.covers(
        CatalogValuePattern.Scalar("LongInteger"),
        CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(1026)")
      )
    )
    assertFalse(
      CatalogShapeMatcher.covers(
        CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(0)"),
        CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(1026)")
      )
    )
    val emptyOrValues = CatalogValuePattern.AnyOf(
      Vector(
        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
        CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
      )
    )
    assertTrue(CatalogShapeMatcher.matches(emptyOrValues, InventoryValueObservation.Repeated(Vector.empty)))
    assertTrue(
      CatalogShapeMatcher.matches(
        emptyOrValues,
        InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Node(1L, "ValDef")))
      )
    )
    assertFalse(
      CatalogShapeMatcher.matches(
        emptyOrValues,
        InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Node(1L, "TypeDef")))
      )
    )
    assertTrue(
      CatalogShapeMatcher.covers(
        emptyOrValues,
        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
      )
    )

  @Test def canonicalNamePatternMatchesOrdinaryAndGeneratedRuntimeNames(): Unit =
    Vector(
      InventoryValueObservation.Name("name"),
      InventoryValueObservation.GeneratedName("name", "$", 1)
    ).foreach(observation => assertTrue(CatalogShapeMatcher.matches(CatalogValuePattern.Name, observation)))
    assertFalse(
      CatalogShapeMatcher.matches(
        CatalogValuePattern.GeneratedName,
        InventoryValueObservation.Name("name")
      )
    )

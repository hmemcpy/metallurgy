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

  @Test def exceptAncestorMatchesUntilTheForbiddenLineageAppears(): Unit =
    val anchor                                        = InventoryAncestor(InventoryKind.Node, "DefDef", Vector(CatalogPathSegment.NamedField("preRhs")))
    val forbidden                                     = InventoryAncestor(InventoryKind.Node, "CaseDef", Vector(CatalogPathSegment.NamedField("pat")))
    val atArgs                                        = InventoryAncestor(
      InventoryKind.Node,
      "AppliedTypeTree",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )
    val pattern                                       = ContextPattern.ParentUnderAnchorExceptAncestor(
      InventoryKind.Node,
      "AppliedTypeTree",
      atArgs.path,
      anchor,
      forbidden
    )
    def context(ancestors: Vector[InventoryAncestor]) =
      Some(InventoryContext(InventoryKind.Node, "AppliedTypeTree", atArgs.path, ancestors))
    val expressionChain                               = context(Vector(anchor))
    val nestedChain                                   = context(Vector(atArgs, anchor))
    val patternChain                                  = context(Vector(forbidden, anchor))
    val deepPatternChain                              = context(Vector(atArgs, forbidden, anchor))
    val wrongAnchor                                   = InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("preRhs")))
    assertTrue(CatalogShapeMatcher.contextMatches(pattern, expressionChain))
    assertTrue(CatalogShapeMatcher.contextMatches(pattern, nestedChain))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(pattern, expressionChain))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(pattern, nestedChain))
    assertFalse(CatalogShapeMatcher.contextMatches(pattern, patternChain))
    assertFalse(CatalogShapeMatcher.contextMatches(pattern, deepPatternChain))
    assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, patternChain))
    assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, deepPatternChain))
    assertFalse(CatalogShapeMatcher.contextMatches(pattern, context(Vector(wrongAnchor))))
    assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, context(Vector(wrongAnchor))))

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
        classification: SourceClassification = SourceClassification.SourceReachable,
        separators: Vector[ParserScannerTokenKind] = Vector.empty
    ): Vector[String] =
      CatalogShapeMatcher
        .select(
          catalog,
          InventoryKind.Node,
          prefix,
          fields,
          direct,
          classification,
          scanner,
          separators
        )
        .map(_.id)

    assertEquals(
      Vector("import-selector-given-bound-qualified-type"),
      selected(
        "Select",
        selectFields,
        Vector(ParserScannerTokenKind.Dot),
        separators = Vector(ParserScannerTokenKind.Dot)
      )
    )
    assertEquals(
      Vector("type-atom-projection"),
      selected(
        "Select",
        selectFields,
        Vector(ParserScannerTokenKind.Hash),
        separators = Vector(ParserScannerTokenKind.Hash)
      )
    )
    assertTrue(
      "Selects without parser-owned separator evidence stay unselected",
      selected(
        "Select",
        selectFields,
        Vector(ParserScannerTokenKind.Dot, ParserScannerTokenKind.Hash),
        separators = Vector.empty
      ).isEmpty
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

  @Test def rootAttachmentRequirementsMatchOneExactNeutralKeyAndValueOnTheSelectedRoot(): Unit =
    val compiler                                                       = inventory(snapshot("/one", 1, Vector.empty))
    val shape                                                          = compiler.shapes.find(_.prefix == "Root").get
    val requirement                                                    = AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using"))
    val base                                                           = completeCatalog(compiler).productions.find(_.id == "Root").get
    val production                                                     = base.copy(
      pattern = base.pattern.copy(requiredAttachments = Vector(requirement))
    )
    val catalog                                                        = Scala3PsiProductionCatalog(Vector(production), focusedRoleInventory(Vector(production)))
    def selected(observed: Vector[AttachmentEvidence]): Vector[String] = CatalogShapeMatcher
      .select(
        catalog,
        shape.kind,
        shape.prefix,
        shape.observation,
        None,
        shape.sourceClassification,
        rootAttachments = observed
      )
      .map(_.id)

    assertEquals(Vector("Root"), selected(Vector(requirement)))
    assertEquals(
      Vector("Root"),
      selected(Vector(AttachmentEvidence("Other", ParserAttachmentValue.Name("value")), requirement))
    )
    val wrongOwner = inventory(
      snapshot("/wrong-owner", 2, Vector.empty)
        .copy(attachments = Vector(ParserTreeAttachment(2L, 0, requirement.keyKind, requirement.value)))
    )
    assertTrue(
      selected(
        wrongOwner.shapes.find(row => row.kind == InventoryKind.Node && row.id == 1L).get.rootAttachments
      ).isEmpty
    )
    Vector(
      Vector.empty,
      Vector(AttachmentEvidence("Other", requirement.value)),
      Vector(AttachmentEvidence(requirement.keyKind, ParserAttachmentValue.Product("Regular"))),
      Vector(requirement, requirement),
      Vector(requirement, AttachmentEvidence(requirement.keyKind, ParserAttachmentValue.Product("Regular")))
    ).foreach(observed => assertTrue(observed.toString, selected(observed).isEmpty))

    val unrelated = AttachmentEvidence("Other", ParserAttachmentValue.Name("value"))
    assertTrue(CatalogShapeMatcher.rootAttachmentConditionMatches(requirement, present = false, Vector.empty))
    assertTrue(CatalogShapeMatcher.rootAttachmentConditionMatches(requirement, present = false, Vector(unrelated)))
    assertTrue(CatalogShapeMatcher.rootAttachmentConditionMatches(requirement, present = true, Vector(requirement)))
    Vector(
      Vector(AttachmentEvidence(requirement.keyKind, ParserAttachmentValue.Product("Regular"))),
      Vector(requirement, requirement),
      Vector(requirement, AttachmentEvidence(requirement.keyKind, ParserAttachmentValue.Product("Regular")))
    ).foreach: observed =>
      assertFalse(CatalogShapeMatcher.rootAttachmentConditionMatches(requirement, present = false, observed))
      assertFalse(CatalogShapeMatcher.rootAttachmentConditionMatches(requirement, present = true, observed))

    val readable = Scala3PsiProductionCatalog.catalogPlanStructure(catalog)
    assertTrue(readable.text.contains("required-attachment\t0\tRoot\t0\tKindOfApply\tProduct(Using)"))
    assertNotEquals(
      Scala3PsiProductionCatalog
        .catalogPlanStructure(Scala3PsiProductionCatalog(Vector(base), focusedRoleInventory(Vector(base))))
        .fingerprint,
      readable.fingerprint
    )

  @Test def ownedRootLineagePrefilterCannotEstablishOwnership(): Unit =
    val qualifier                                     = InventoryAncestor(
      InventoryKind.Node,
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier"))
    )
    val apply                                         = InventoryAncestor(
      InventoryKind.Node,
      "Apply",
      Vector(CatalogPathSegment.NamedField("fun"))
    )
    val owner                                         = InventoryAncestor(
      InventoryKind.Node,
      "ValDef",
      Vector(CatalogPathSegment.NamedField("preRhs"))
    )
    val outer                                         = InventoryAncestor(
      InventoryKind.Node,
      "PackageDef",
      Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
    )
    val route                                         = OwnedRootRoute(
      "definition-payload-apply",
      Vector(qualifier, apply),
      owner,
      outer,
      Some(RepeatedOwnedRootEdge(1, qualifier))
    )
    val pattern                                       = ContextPattern.DescendantOfOwnedRoot(Vector(route))
    def context(ancestors: Vector[InventoryAncestor]) =
      Some(InventoryContext(qualifier.ownerKind, qualifier.ownerPrefix, qualifier.path, ancestors))

    var calls                                                                     = 0
    def matches(ancestors: Vector[InventoryAncestor], ownsRoot: Boolean): Boolean =
      calls = 0
      val result = CatalogShapeMatcher.contextMatches(
        pattern,
        context(ancestors),
        _ =>
          calls += 1
          ownsRoot
      )
      result

    Vector(
      Vector(apply, owner, outer),
      Vector(qualifier, apply, owner, outer),
      Vector(qualifier, qualifier, apply, owner, outer)
    ).foreach: ancestors =>
      assertFalse(matches(ancestors, ownsRoot = false))
      assertEquals(1, calls)
      assertTrue(matches(ancestors, ownsRoot = true))
      assertEquals(1, calls)

    assertFalse(matches(Vector(qualifier, owner, outer), ownsRoot = true))
    assertEquals(0, calls)
    assertFalse(matches(Vector(qualifier, apply, qualifier, owner, outer), ownsRoot = true))
    assertEquals(0, calls)
    assertFalse(CatalogShapeMatcher.contextMatches(pattern, context(Vector(apply, owner, outer))))
    assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, context(Vector(apply, owner, outer))))

  @Test def ownedRootRouteRequiresOneExactBoundedOutputFreeLineage(): Unit =
    def instance(id: Long) = ProductionInstanceId(InventoryKind.Node, id, None)
    val candidate          = instance(1)
    val intermediate       = instance(2)
    val root               = instance(3)
    val definition         = instance(4)
    val outer              = instance(5)
    val repeatedOne        = instance(6)
    val repeatedTwo        = instance(7)
    val descendantEdge     = InventoryAncestor(
      InventoryKind.Node,
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier"))
    )
    val rootEdge           = InventoryAncestor(
      InventoryKind.Node,
      "Apply",
      Vector(CatalogPathSegment.NamedField("fun"))
    )
    val ownerEdge          = InventoryAncestor(
      InventoryKind.Node,
      "ValDef",
      Vector(CatalogPathSegment.NamedField("preRhs"))
    )
    val outerEdge          = InventoryAncestor(
      InventoryKind.Node,
      "PackageDef",
      Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
    )
    val route              = OwnedRootRoute(
      "definition-payload-apply",
      Vector(descendantEdge, rootEdge),
      ownerEdge,
      outerEdge
    )
    val parents            = Map(
      candidate    -> Vector(RuntimeParentEdge(intermediate, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
      intermediate -> Vector(RuntimeParentEdge(root, Vector(ParserFieldPathSegment.NamedField("fun")))),
      root         -> Vector(RuntimeParentEdge(definition, Vector(ParserFieldPathSegment.NamedField("preRhs")))),
      definition   -> Vector(
        RuntimeParentEdge(
          outer,
          Vector(ParserFieldPathSegment.NamedField("stats"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    val catalog            = Scala3PsiProductionCatalog.Reviewed.productions.map(p => p.id -> p).toMap
    val selected           = Map(
      intermediate -> catalog("payload-output-free-select"),
      root         -> catalog(route.rootProductionId)
    )
    val prefixes           = Map(
      candidate    -> "Ident",
      intermediate -> "Select",
      root         -> "Apply",
      definition   -> "ValDef",
      outer        -> "PackageDef"
    )
    val positions          = Map(
      candidate    -> ParserNodePosition.Positioned(PcSourceRange(4, 7), 4, ParserPositionProvenance.SourceDerived),
      intermediate -> ParserNodePosition.Positioned(PcSourceRange(4, 11), 4, ParserPositionProvenance.SourceDerived),
      root         -> ParserNodePosition.Positioned(PcSourceRange(0, 12), 0, ParserPositionProvenance.SourceDerived),
      definition   -> ParserNodePosition.Positioned(PcSourceRange(0, 12), 0, ParserPositionProvenance.SourceDerived),
      outer        -> ParserNodePosition.Positioned(PcSourceRange(0, 12), 0, ParserPositionProvenance.SourceDerived)
    )
    def matches(
        candidateValue: ProductionInstanceId = candidate,
        routeValue: OwnedRootRoute = route,
        parentValues: Map[ProductionInstanceId, Vector[RuntimeParentEdge]] = parents,
        selectedValues: Map[ProductionInstanceId, Scala3PsiProduction] = selected,
        positionValues: Map[ProductionInstanceId, ParserNodePosition] = positions
    ) = OwnedRootRouteMatcher.matches(
      candidateValue,
      routeValue,
      parentValues,
      selectedValues,
      prefixes,
      positionValues
    )

    assertTrue(matches())
    assertTrue(matches(positionValues = positions.updated(candidate, ParserNodePosition.Absent)))
    assertFalse(matches(routeValue = route.copy(rootProductionId = "definition-payload-ident")))
    assertFalse(matches(routeValue = route.copy(descendantPath = route.descendantPath.drop(1))))
    assertFalse(matches(routeValue = route.copy(descendantPath = route.descendantPath :+ rootEdge)))
    assertFalse(matches(parentValues = parents - intermediate))
    assertFalse(matches(parentValues = parents.updated(candidate, parents(candidate) :+ parents(candidate).head)))
    assertFalse(matches(selectedValues = selected.updated(intermediate, catalog("payload-descendant-ident"))))
    val repeatedRoute    = route.copy(repeatedEdge = Some(RepeatedOwnedRootEdge(1, descendantEdge)))
    assertTrue(matches(routeValue = repeatedRoute))
    val repeatedParents  = parents ++ Map(
      intermediate -> Vector(RuntimeParentEdge(repeatedOne, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
      repeatedOne  -> Vector(RuntimeParentEdge(repeatedTwo, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
      repeatedTwo  -> Vector(RuntimeParentEdge(root, Vector(ParserFieldPathSegment.NamedField("fun"))))
    )
    val repeatedSelected = selected ++ Map(
      repeatedOne -> catalog("payload-output-free-select"),
      repeatedTwo -> catalog("payload-output-free-select")
    )
    val repeatedPrefixes = prefixes ++ Map(repeatedOne -> "Select", repeatedTwo -> "Select")
    assertTrue(
      OwnedRootRouteMatcher.matches(
        candidate,
        repeatedRoute,
        repeatedParents,
        repeatedSelected,
        repeatedPrefixes,
        positions.withDefaultValue(ParserNodePosition.Absent)
      )
    )
    assertFalse(
      OwnedRootRouteMatcher.matches(
        candidate,
        repeatedRoute,
        repeatedParents.updated(
          repeatedOne,
          Vector(RuntimeParentEdge(repeatedTwo, Vector(ParserFieldPathSegment.NamedField("unknown"))))
        ),
        repeatedSelected,
        repeatedPrefixes,
        positions.withDefaultValue(ParserNodePosition.Absent)
      )
    )
    assertFalse(
      OwnedRootRouteMatcher.matches(
        candidate,
        repeatedRoute,
        repeatedParents,
        repeatedSelected.updated(repeatedOne, catalog("payload-descendant-ident")),
        repeatedPrefixes,
        positions.withDefaultValue(ParserNodePosition.Absent)
      )
    )
    assertFalse(
      OwnedRootRouteMatcher.matches(
        candidate,
        repeatedRoute,
        repeatedParents.updated(repeatedOne, repeatedParents(repeatedOne) :+ repeatedParents(repeatedOne).head),
        repeatedSelected,
        repeatedPrefixes,
        positions.withDefaultValue(ParserNodePosition.Absent)
      )
    )
    assertFalse(
      matches(
        positionValues = positions.updated(
          candidate,
          ParserNodePosition.Positioned(PcSourceRange(11, 13), 11, ParserPositionProvenance.SourceDerived)
        )
      )
    )

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[psiproducer] trait Scala3PsiProductionCatalogTestSupport:
  protected final def planned(
      value: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      catalog: Scala3PsiProductionCatalog,
      aggregate: AggregatedCompilerProductionInventory,
      surface: ScalaPsiSurfaceInventory
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    PreparedProductionCatalog.prepare(catalog, aggregate, surface) match
      case Left(errors)    => Left(WholeFilePlanningFailure.InvalidCatalog(errors))
      case Right(prepared) => WholeFileProductionPlanner.plan(value, evidence, prepared)

  protected final def inventory(value: ParserSyntaxSnapshot): CompilerRuntimeInventory =
    CompilerRuntimeInventory.from(value).fold(f => throw new AssertionError(f.toString), identity)

  protected final def aggregate(values: Vector[CompilerRuntimeInventory]): AggregatedCompilerProductionInventory =
    AggregatedCompilerProductionInventory.aggregate(values).fold(f => throw new AssertionError(f.toString), identity)

  protected final def row(
      value: InventoryValueObservation,
      declaration: Option[CatalogValuePattern] = None
  ): CompilerShapeInventoryRow =
    CompilerShapeInventoryRow(
      InventoryKind.Node,
      1L,
      "Observed",
      Vector.empty,
      Vector(InventoryFieldObservation("value", value, declaration)),
      Vector(InventoryContext(InventoryKind.Node, "Owner", Vector(CatalogPathSegment.NamedField("value")))),
      SourceClassification.SourceReachable
    )

  protected final def failures(value: ParserSyntaxSnapshot): Vector[InventoryFailure] =
    CompilerRuntimeInventory.from(value).left.toOption.get

  protected final def completeCatalog(compiler: CompilerRuntimeInventory): Scala3PsiProductionCatalog =
    val productions = compiler.shapes.map: shape =>
      def referencedProduction(value: InventoryValueObservation): Option[String] = value match
        case InventoryValueObservation.Node(_, prefix)       => Some(prefix)
        case InventoryValueObservation.Positioned(_, prefix) => Some(prefix)
        case InventoryValueObservation.Optional(value)       => value.flatMap(referencedProduction)
        case InventoryValueObservation.Repeated(values)      => values.flatMap(referencedProduction).headOption
        case InventoryValueObservation.Product(prefix, _)    => Some(prefix)
        case _: InventoryValueObservation.Name | _: InventoryValueObservation.BacktickedName |
            _: InventoryValueObservation.GeneratedName | _: InventoryValueObservation.Scalar |
            _: InventoryValueObservation.Unsupported =>
          None
      val childFields                                                            = shape.observation.flatMap(field => referencedProduction(field.value).map(field.name -> _))
      val childFieldNames                                                        = childFields.map(_._1).toSet
      Scala3PsiProduction(
        id = shape.prefix,
        grammarRoleId = GrammarRoleId(s"test.grammar.${shape.prefix}"),
        pattern = CompilerProductionPattern(
          shape.kind,
          shape.prefix,
          shape.patternFields,
          (if shape.contexts.isEmpty then Vector(ContextPattern.Root)
           else
             shape.contexts.map(context =>
               context.ancestors.headOption match
                 case Some(ancestor) =>
                   ContextPattern.ParentWithAncestor(
                     context.ownerKind,
                     context.ownerPrefix,
                     context.path,
                     ancestor
                   )
                 case None           => ContextPattern.Parent(context.ownerKind, context.ownerPrefix, context.path)
             )
          )
            .map(CompilerProductionContextPattern(_, shape.sourceClassification))
        ),
        dispositions = shape.patternFields.map(field =>
          FieldDisposition(
            field.name,
            if childFieldNames(field.name) then FieldDispositionKind.Child else FieldDispositionKind.SemanticOnly
          )
        ),
        children = childFields.map: (field, production) =>
          val role = if childFields.size == 1 then "child" else s"child-$field"
          ChildDeclaration(role, field, ChildCardinality.Repeated(0, None), production)
        ,
        terminals =
          if childFields.isEmpty then
            Vector(
              TerminalDeclaration(
                "contents",
                TerminalIntervalSelector.WholeProduction,
                TerminalLeafTarget.Parent,
                OccurrenceCardinality.ExactlyOne,
                PsiOutputRoleId.SourceTerminal
              )
            )
          else Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = s"element.${shape.prefix}",
        targetRequirement = TargetRequirement.Compatible,
        accessors = Vector.empty,
        persistence = PersistenceObligations.NotApplicable,
        outputRoleId = Some(PsiOutputRoleId(s"test.output.${shape.prefix}"))
      )
    Scala3PsiProductionCatalog(productions, focusedRoleInventory(productions))

  protected final def focusedRoleInventory(productions: Vector[Scala3PsiProduction]): StableRoleInventory =
    StableRoleInventory(
      productions.flatMap(_.grammarRoleIds).toSet,
      productions
        .flatMap(production =>
          production.terminals.map(_.outputRoleId) ++
            production.effectiveOutputRealizations.flatMap(_.template.composites.map(_.outputRoleId))
        )
        .toSet
    )

  protected final def annotationModifierSnapshot: ParserSyntaxSnapshot =
    val source                                                                             = "@deprecated(\"m\", \"1\") final"
    def positioned(start: Int, end: Int, point: Int, provenance: ParserPositionProvenance) =
      ParserNodePosition.Positioned(PcSourceRange(start, end), point, provenance)
    val modifiers                                                                          = ParserFieldValue.Product(
      "Modifiers",
      Vector(
        ParserSyntaxField("flags", ParserFieldValue.Scalar(ParserScalar.LongInteger(0L))),
        ParserSyntaxField("privateWithin", ParserFieldValue.Name("")),
        ParserSyntaxField("annotations", ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2)))),
        ParserSyntaxField("mods", ParserFieldValue.Repeated(Vector(ParserFieldValue.Positioned(10))))
      )
    )
    val root                                                                               = ParserSyntaxNode(
      1,
      "TypeDef",
      Vector(ParserSyntaxField("mods", modifiers)),
      positioned(0, source.length, 0, ParserPositionProvenance.SourceDerived),
      Vector.empty
    )
    val annotation                                                                         = ParserSyntaxNode(
      2,
      "Apply",
      Vector(
        ParserSyntaxField("fun", ParserFieldValue.Node(3)),
        ParserSyntaxField(
          "args",
          ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(6), ParserFieldValue.Node(7)))
        )
      ),
      positioned(0, 21, 1, ParserPositionProvenance.SourceDerived),
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.NestedProductBoundary("Modifiers"),
            ParserFieldPathSegment.NamedField("annotations"),
            ParserFieldPathSegment.RepeatedIndex(0)
          )
        )
      )
    )
    val constructor                                                                        = ParserSyntaxNode(
      3,
      "Select",
      Vector(
        ParserSyntaxField("qualifier", ParserFieldValue.Node(4)),
        ParserSyntaxField("name", ParserFieldValue.Name("<init>"))
      ),
      positioned(0, 11, 0, ParserPositionProvenance.Synthetic),
      Vector(ParserNodeOccurrence(2, Vector(ParserFieldPathSegment.NamedField("fun"))))
    )
    val fresh                                                                              = ParserSyntaxNode(
      4,
      "New",
      Vector(ParserSyntaxField("tpt", ParserFieldValue.Node(5))),
      positioned(0, 11, 0, ParserPositionProvenance.Synthetic),
      Vector(ParserNodeOccurrence(3, Vector(ParserFieldPathSegment.NamedField("qualifier"))))
    )
    val designator                                                                         = ParserSyntaxNode(
      5,
      "Ident",
      Vector(ParserSyntaxField("name", ParserFieldValue.Name("deprecated"))),
      positioned(1, 11, 1, ParserPositionProvenance.SourceDerived),
      Vector(ParserNodeOccurrence(4, Vector(ParserFieldPathSegment.NamedField("tpt"))))
    )
    def literal(id: Long, start: Int, end: Int, value: String, index: Int)                 = ParserSyntaxNode(
      id,
      "Literal",
      Vector(
        ParserSyntaxField(
          "const",
          ParserFieldValue.Product(
            "",
            Vector(ParserSyntaxField("", ParserFieldValue.Scalar(ParserScalar.Text(value))))
          )
        )
      ),
      positioned(start, end, start, ParserPositionProvenance.SourceDerived),
      Vector(
        ParserNodeOccurrence(
          2,
          Vector(ParserFieldPathSegment.NamedField("args"), ParserFieldPathSegment.RepeatedIndex(index))
        )
      )
    )
    val keyword                                                                            = ParserPositionedSyntax(
      10,
      "Final",
      Vector.empty,
      positioned(22, 27, 22, ParserPositionProvenance.SourceDerived),
      Vector(
        ParserPositionedOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.NestedProductBoundary("Modifiers"),
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.RepeatedIndex(0)
          )
        )
      )
    )
    val base                                                                               = snapshot("/annotation-modifier", 1, Vector.empty)
    base.copy(
      sourceUri = base.sourceUri,
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      compilerOptions = base.compilerOptions,
      rootNodeId = 1,
      nodes = Vector(
        root,
        annotation,
        constructor,
        fresh,
        designator,
        literal(6, 12, 15, "m", 0),
        literal(7, 17, 20, "1", 1)
      ),
      positioned = Vector(keyword),
      comments = Vector.empty,
      diagnostics = Vector.empty,
      capabilities = base.capabilities,
      compilerIdentity = base.compilerIdentity,
      endMarkers = Vector.empty,
      runtimeSupplements = Vector.empty,
      attachments = Vector.empty,
      scannerTokens = Vector(
        ParserScannerToken(
          0,
          0,
          "'@'",
          ParserScannerTokenKind.Other,
          PcSourceRange(0, 1),
          0,
          ParserPositionProvenance.SourceDerived
        )
      )
    )

  protected final def surfaces(catalog: Scala3PsiProductionCatalog): ScalaPsiSurfaceInventory =
    ScalaPsiSurfaceInventory(
      catalog.productions
        .map(p =>
          ScalaPsiSurfaceRow(
            p.targetSurfaceId,
            SurfaceFactKind.Element,
            None,
            FactStatus.Available,
            SurfaceClassification.Derived
          )
        )
        .distinct
    )

  protected final def node(id: Long, value: ParserFieldValue) = ParserSyntaxNode(
    id,
    if id == 1 then "Root" else "Child",
    Vector(ParserSyntaxField("children", value)),
    ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived),
    Vector.empty
  )

  protected final def snapshot(path: String, loader: Long, options: Vector[String]): ParserSyntaxSnapshot =
    val source = "x"
    val child  = ParserSyntaxNode(
      2,
      "Child",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived),
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    ParserSyntaxSnapshot(
      ParserSourceUri.from("file:///Catalog.scala").toOption.get,
      source,
      ParserSyntaxSnapshot.digest(source),
      source.length,
      options,
      1,
      Vector(node(1, ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2)))), child),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Scala3ParserCapabilities(
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
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
        Scala3ParserArtifactCoordinate("org", "compiler", "3"),
        Vector(
          Scala3ParserArtifactIdentity("a.jar", path, 1, "a", 0),
          Scala3ParserArtifactIdentity("b.jar", path, 2, "b", 1)
        ),
        Scala3ParserLoaderIdentity(loader)
      ),
      Vector.empty
    )

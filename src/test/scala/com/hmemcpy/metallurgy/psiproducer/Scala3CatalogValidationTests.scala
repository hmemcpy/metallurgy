package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait Scala3CatalogValidationTests extends Scala3PsiProductionCatalogTestSupport:
  @Test def emptyCatalogFailsValidationForNonemptyInventory(): Unit =
    val value    = snapshot("/one", 1, Vector.empty)
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val result   = planned(
      value,
      evidence,
      Scala3PsiProductionCatalog.Empty,
      aggregate(Vector(inventory(value))),
      ScalaPsiSurfaceInventory(Vector.empty)
    )
    val errors   = result.left.toOption.get.asInstanceOf[WholeFilePlanningFailure.InvalidCatalog].errors
    assertTrue(errors.exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape]))

  @Test def roleValidationRejectsMissingUnknownAndEvidenceDerivedIdentities(): Unit =
    val compiler = inventory(snapshot("/roles", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.id == "Root").get
    def errors(
        production: Scala3PsiProduction,
        stableRoles: StableRoleInventory = base.stableRoles
    ): Vector[CatalogValidationError] =
      val catalog = base.copy(
        productions = base.productions.map(value => if value.id == production.id then production else value),
        stableRoles = stableRoles
      )
      Scala3PsiProductionCatalogValidator.validateExecutable(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(root.copy(outputRoleId = None)).contains(CatalogValidationError.MissingDefaultOutputRole(root.id))
    )

    val unknownGrammar  = GrammarRoleId("test.grammar.unknown")
    assertTrue(
      errors(root.copy(grammarRoleId = unknownGrammar))
        .contains(CatalogValidationError.UnknownGrammarRole(root.id, unknownGrammar))
    )
    val evidenceGrammar = GrammarRoleId(root.pattern.prefix)
    val evidenceErrors  = errors(root.copy(grammarRoleId = evidenceGrammar))
    assertTrue(
      evidenceErrors.contains(
        CatalogValidationError.CompilerDerivedGrammarRole(root.id, evidenceGrammar, root.pattern.prefix)
      )
    )
    assertTrue(
      evidenceErrors.contains(CatalogValidationError.CatalogAlternativeDerivedGrammarRole(root.id, evidenceGrammar))
    )

    val unknownOutput = PsiOutputRoleId("test.output.unknown")
    assertTrue(
      errors(root.copy(outputRoleId = Some(unknownOutput)))
        .contains(CatalogValidationError.UnknownOutputRole(root.id, "self", unknownOutput))
    )
    val hostOutput    = PsiOutputRoleId(root.targetSurfaceId)
    assertTrue(
      errors(
        root.copy(outputRoleId = Some(hostOutput)),
        base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + hostOutput)
      ).contains(CatalogValidationError.HostDerivedOutputRole(root.id, "self", hostOutput, root.targetSurfaceId))
    )

    val child                   = base.productions.find(_.id == "Child").get
    val childTerminal           = child.terminals.head
    val otherAlternativeGrammar = GrammarRoleId(child.id)
    assertTrue(
      errors(
        root.copy(grammarRoleId = otherAlternativeGrammar),
        base.stableRoles.copy(grammarRoles = base.stableRoles.grammarRoles + otherAlternativeGrammar)
      ).contains(CatalogValidationError.CatalogAlternativeDerivedGrammarRole(root.id, otherAlternativeGrammar))
    )
    val otherCompilerGrammar    = GrammarRoleId(child.pattern.prefix)
    assertTrue(
      errors(
        root.copy(grammarRoleId = otherCompilerGrammar),
        base.stableRoles.copy(grammarRoles = base.stableRoles.grammarRoles + otherCompilerGrammar)
      ).contains(
        CatalogValidationError.CompilerDerivedGrammarRole(root.id, otherCompilerGrammar, child.pattern.prefix)
      )
    )
    assertTrue(
      errors(child.copy(terminals = Vector(childTerminal.copy(outputRoleId = unknownOutput))))
        .contains(CatalogValidationError.UnknownOutputRole(child.id, childTerminal.id, unknownOutput))
    )
    val otherHostOutput         = PsiOutputRoleId(child.targetSurfaceId)
    assertTrue(
      errors(
        root.copy(outputRoleId = Some(otherHostOutput)),
        base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + otherHostOutput)
      ).contains(CatalogValidationError.HostDerivedOutputRole(root.id, "self", otherHostOutput, child.targetSurfaceId))
    )
    val installedHostSurface    = "test.host.installed-unreferenced"
    val installedHostRole       = PsiOutputRoleId(installedHostSurface)
    val installedHostProduction = root.copy(outputRoleId = Some(installedHostRole))
    val installedHostCatalog    = base.copy(
      productions = base.productions.updated(0, installedHostProduction),
      stableRoles = base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + installedHostRole)
    )
    val installedHostSurfaces   = surfaces(installedHostCatalog).copy(rows =
      surfaces(installedHostCatalog).rows :+
        ScalaPsiSurfaceRow(
          installedHostSurface,
          SurfaceFactKind.Element,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
    )
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validateExecutable(installedHostCatalog, compiler, installedHostSurfaces)
        .contains(
          CatalogValidationError.HostDerivedOutputRole(
            root.id,
            "self",
            installedHostRole,
            installedHostSurface
          )
        )
    )
    val tokenSurface            = "test.host.token"
    val tokenHostRole           = PsiOutputRoleId(tokenSurface)
    val hostTerminal            = childTerminal.copy(
      target = TerminalLeafTarget.Token(tokenSurface),
      outputRoleId = tokenHostRole
    )
    assertTrue(
      errors(
        child.copy(terminals = Vector(hostTerminal)),
        base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + tokenHostRole)
      ).contains(CatalogValidationError.HostDerivedOutputRole(child.id, childTerminal.id, tokenHostRole, tokenSurface))
    )

    val extraGrammar    = GrammarRoleId("test.grammar.unreferenced")
    val extraOutput     = PsiOutputRoleId("test.output.unreferenced")
    val expandedRoles   = base.stableRoles.copy(
      grammarRoles = base.stableRoles.grammarRoles + extraGrammar,
      outputRoles = base.stableRoles.outputRoles + extraOutput
    )
    val inventoryErrors = Scala3PsiProductionCatalogValidator.validate(
      base.copy(stableRoles = expandedRoles),
      compiler,
      surfaces(base)
    )
    assertTrue(inventoryErrors.contains(CatalogValidationError.UnreferencedGrammarRole(extraGrammar)))
    assertTrue(inventoryErrors.contains(CatalogValidationError.UnreferencedOutputRole(extraOutput)))

    val tokenCatalog  = base.copy(productions = base.productions.map:
      case production if production.id == child.id =>
        production.copy(terminals = Vector(childTerminal.copy(target = TerminalLeafTarget.Token(tokenSurface))))
      case production                              => production
    )
    val tokenSurfaces = surfaces(tokenCatalog).copy(rows =
      surfaces(tokenCatalog).rows :+
        ScalaPsiSurfaceRow(
          tokenSurface,
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.Derived
        )
    )
    val tokenReport   = Scala3PsiProductionCoverageReport.markdown(
      tokenCatalog,
      aggregate(Vector(compiler)),
      tokenSurfaces
    )
    assertTrue(tokenReport.contains(s"${child.id}:terminal:${childTerminal.id}->$tokenSurface"))
    assertTrue(tokenReport.contains(s"host-targets=element.Child,$tokenSurface"))

    val grammarFailure     = Scala3SyntaxCapabilityFailure.from(
      "digest",
      Scala3SyntaxCapabilityStage.Catalog,
      Vector(CatalogValidationError.UnknownGrammarRole(root.id, unknownGrammar)),
      ParserPreparationEpoch(1),
      None
    )
    assertEquals(
      Scala3SyntaxCapabilityRequirement.GrammarRole(Some(unknownGrammar.value)),
      grammarFailure.requirement
    )
    val outputFailure      = Scala3SyntaxCapabilityFailure.from(
      "digest",
      Scala3SyntaxCapabilityStage.Catalog,
      Vector(CatalogValidationError.UnknownOutputRole(root.id, "self", unknownOutput)),
      ParserPreparationEpoch(1),
      None
    )
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(Some(unknownOutput.value)),
      outputFailure.requirement
    )
    val unaccountedFailure = Scala3SyntaxCapabilityFailure.from(
      "digest",
      Scala3SyntaxCapabilityStage.Catalog,
      Vector(CatalogValidationError.UnaccountedSyntaxSurface(tokenSurface)),
      ParserPreparationEpoch(1),
      None
    )
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(None),
      unaccountedFailure.requirement
    )

  @Test def executableValidationDoesNotRequireAPartialCatalogToOwnUnrelatedInstalledSyntaxSurfaces(): Unit =
    val compiler         = inventory(snapshot("/one", 1, Vector.empty))
    val catalog          = completeCatalog(compiler)
    val unrelated        = ScalaPsiSurfaceRow(
      "element.Unrelated",
      SurfaceFactKind.Element,
      None,
      FactStatus.Available,
      SurfaceClassification.SyntaxContract
    )
    val surfaceInventory = surfaces(catalog).copy(rows = surfaces(catalog).rows :+ unrelated)
    val aggregate        = this.aggregate(Vector(compiler))
    val reportGate       = Scala3PsiProductionCatalogValidator.validate(catalog, aggregate, surfaceInventory)
    assertTrue(reportGate.contains(CatalogValidationError.UnaccountedSyntaxSurface(unrelated.id)))
    assertTrue(Scala3PsiProductionCatalogValidator.validateExecutable(catalog, aggregate, surfaceInventory).isEmpty)

  @Test def validatorRejectsAmbiguousGeneralAndSpecificContexts(): Unit =
    val compiler   = inventory(snapshot("/one", 1, Vector.empty))
    val base       = completeCatalog(compiler)
    val child      = base.productions.find(_.pattern.prefix == "Child").get
    val ambiguous  =
      child.copy(
        id = "Child.general",
        pattern = child.pattern.copy(occurrences =
          Vector(
            CompilerProductionContextPattern(ContextPattern.Any, child.pattern.occurrences.head.sourceClassification)
          )
        )
      )
    val catalog    = base.copy(productions = base.productions :+ ambiguous)
    val validation = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))
    assertTrue(validation.exists(_.isInstanceOf[CatalogValidationError.AmbiguousCompilerShape]))

  @Test def aggregatedValidationCoversShapesAndSourceClassificationsBeyondOneSnapshot(): Unit =
    val compiler   = inventory(snapshot("/one", 1, Vector.empty))
    val catalog    = completeCatalog(compiler)
    val root       = compiler.shapes.find(_.prefix == "Root").get
    val synthetic  = root.copy(sourceClassification = SourceClassification.Synthetic)
    val additional = row(InventoryValueObservation.Name("x"))
    val aggregated = aggregate(
      Vector(
        compiler,
        compiler.copy(
          parserEvidenceFingerprint = "additional",
          shapes = Vector(synthetic, additional)
        )
      )
    )
    val validation = Scala3PsiProductionCatalogValidator.validate(catalog, aggregated, surfaces(catalog))
    assertTrue(
      validation.contains(
        CatalogValidationError.UncoveredCompilerShape(
          root.kind,
          root.prefix,
          None,
          SourceClassification.Synthetic
        )
      )
    )
    assertTrue(
      validation.exists:
        case CatalogValidationError.UncoveredCompilerShape(_, "Observed", _, _) => true
        case _                                                                  => false
    )

  @Test def aggregatedValidationRejectsCatalogProductionsAbsentFromCanonicalInventory(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val stale    = base.productions.head.copy(
      id = "stale",
      pattern = base.productions.head.pattern.copy(prefix = "Removed")
    )
    val catalog  = base.copy(productions = base.productions :+ stale)
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate(Vector(compiler)), surfaces(catalog))
        .contains(CatalogValidationError.UnrepresentedCatalogProduction("stale", stale.grammarRoleId))
    )

  @Test def aggregatedValidationPreservesContextAndSourceClassificationAssociations(): Unit =
    val compiler      = inventory(snapshot("/one", 1, Vector.empty))
    val root          = compiler.shapes.find(_.prefix == "Root").get
    val parentContext = InventoryContext(
      InventoryKind.Node,
      "Owner",
      Vector(CatalogPathSegment.NamedField("value"))
    )
    val paired        = aggregate(
      Vector(
        compiler.copy(shapes =
          Vector(
            root.copy(contexts = Vector.empty, sourceClassification = SourceClassification.SourceReachable),
            root.copy(contexts = Vector(parentContext), sourceClassification = SourceClassification.Synthetic)
          )
        )
      )
    )
    val base          = completeCatalog(compiler).productions.find(_.pattern.prefix == "Root").get
    val pattern       = base.pattern.copy(occurrences =
      Vector(
        CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable),
        CompilerProductionContextPattern(
          ContextPattern.Parent(parentContext.ownerKind, parentContext.ownerPrefix, parentContext.path),
          SourceClassification.Synthetic
        )
      )
    )
    val production    = base.copy(pattern = pattern)
    val catalog       = Scala3PsiProductionCatalog(Vector(production), focusedRoleInventory(Vector(production)))
    assertFalse(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, paired, surfaces(catalog))
        .exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape])
    )

    val crossed = paired.copy(productions =
      paired.productions.map(row =>
        row.copy(occurrences = row.occurrences.map {
          case CompilerProductionContext(None, _, _, _, _)          =>
            CompilerProductionContext(None, SourceClassification.Synthetic)
          case CompilerProductionContext(Some(context), _, _, _, _) =>
            CompilerProductionContext(Some(context), SourceClassification.SourceReachable)
        })
      )
    )
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, crossed, surfaces(catalog))
        .exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape])
    )

  @Test def aggregatedValidationRejectsAnUnobservedOccurrenceAlternative(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.pattern.prefix == "Root").get
    val stale    = root.copy(pattern =
      root.pattern.copy(occurrences =
        root.pattern.occurrences :+
          CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.Synthetic)
      )
    )
    val catalog  = base.copy(productions = base.productions.map(p => if p.id == root.id then stale else p))
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate(Vector(compiler)), surfaces(catalog))
        .contains(CatalogValidationError.UnrepresentedCatalogProduction(root.id, root.grammarRoleId))
    )

  @Test def aggregatedValidationRejectsWildcardOccurrenceAlternatives(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.pattern.prefix == "Root").get
    val stale    = root.copy(pattern =
      root.pattern.copy(occurrences =
        root.pattern.occurrences :+
          CompilerProductionContextPattern(ContextPattern.Any, SourceClassification.SourceReachable)
      )
    )
    val catalog  = base.copy(productions = base.productions.map(p => if p.id == root.id then stale else p))
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate(Vector(compiler)), surfaces(catalog))
        .contains(CatalogValidationError.UnrepresentedCatalogProduction(root.id, root.grammarRoleId))
    )

  @Test def validatorRequiresExactFieldDispositionsAndChildDeclarations(): Unit =
    val compiler                                                             = inventory(snapshot("/one", 1, Vector.empty))
    val base                                                                 = completeCatalog(compiler)
    val root                                                                 = base.productions.find(_.pattern.prefix == "Root").get
    def errors(updated: Scala3PsiProduction): Vector[CatalogValidationError] =
      val catalog = base.copy(productions = base.productions.map(p => if p.id == root.id then updated else p))
      Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(root.copy(dispositions = Vector.empty))
        .exists(_.isInstanceOf[CatalogValidationError.MissingFieldDisposition])
    )
    assertTrue(
      errors(root.copy(dispositions = root.dispositions ++ root.dispositions))
        .exists(_.isInstanceOf[CatalogValidationError.DuplicateFieldDisposition])
    )
    assertTrue(
      errors(root.copy(children = Vector.empty))
        .exists(_.isInstanceOf[CatalogValidationError.MissingChildDeclaration])
    )

  @Test def validatorRejectsStructurallyIncompleteCatalogDeclarations(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.pattern.prefix == "Root").get
    val child    = base.productions.find(_.pattern.prefix == "Child").get
    val invalid  = root.copy(
      pattern = root.pattern.copy(occurrences = Vector.empty),
      children = root.children.map(
        _.copy(
          roleId = "duplicate",
          productionId = "missing",
          cardinality = ChildCardinality.Repeated(2, Some(1))
        )
      ) ++ root.children.map(_.copy(roleId = "duplicate")),
      terminals = Vector.fill(2)(
        TerminalDeclaration(
          "duplicate",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Repeated(-1, None),
          PsiOutputRoleId.SourceTerminal
        )
      ) :+ TerminalDeclaration(
        "gap",
        TerminalIntervalSelector.ChildGap("duplicate", "absent"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      layouts = Vector.empty,
      recovery = RecoveryPolicy.DiagnosticBound(ParserDiagnosticSeverity.Error, Vector.empty),
      accessors = Vector.fill(2)(AccessorObligation("accessor", required = true))
    )
    val catalog  = base.copy(productions = base.productions.map(p => if p.id == root.id then invalid else p))
    val surface  = surfaces(catalog).copy(rows =
      surfaces(catalog).rows :+
        ScalaPsiSurfaceRow(
          "accessor",
          SurfaceFactKind.PublicAccessor,
          None,
          FactStatus.Available,
          SurfaceClassification.Derived
        )
    )
    val errors   = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surface)
    Vector(
      CatalogValidationError.EmptyOccurrencePatterns(root.id),
      CatalogValidationError.DuplicateChildRoleId(root.id, "duplicate"),
      CatalogValidationError.UnknownChildProductionId(root.id, "missing"),
      CatalogValidationError.InvalidChildCardinality(root.id, "duplicate"),
      CatalogValidationError.DuplicateTerminalId(root.id, "duplicate"),
      CatalogValidationError.InvalidTerminalCardinality(root.id, "duplicate"),
      CatalogValidationError.DuplicateAccessorObligation(root.id, "accessor"),
      CatalogValidationError.EmptyLayoutAlternatives(root.id),
      CatalogValidationError.EmptyRecoveryAlternatives(root.id),
      CatalogValidationError.UnknownTerminalChildRole(root.id, "absent")
    ).foreach(error => assertTrue(error.toString, errors.contains(error)))
    assertFalse(errors.contains(CatalogValidationError.UnknownChildProductionId(root.id, child.id)))

  @Test def validatorRejectsDuplicateDeclaredRootAttachmentRequirements(): Unit =
    val compiler                                                             = inventory(snapshot("/attachment-requirements", 1, Vector.empty))
    val base                                                                 = completeCatalog(compiler)
    val root                                                                 = base.productions.find(_.pattern.prefix == "Root").get
    val requirement                                                          = AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using"))
    def errors(updated: Scala3PsiProduction): Vector[CatalogValidationError] =
      val catalog = base.copy(productions = base.productions.map(p => if p.id == root.id then updated else p))
      Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(root.copy(pattern = root.pattern.copy(requiredAttachments = Vector(requirement, requirement))))
        .contains(CatalogValidationError.DuplicateRequiredAttachment(root.id, requirement.keyKind))
    )
    val realization = root.effectiveOutputRealizations.head.copy(evidenceConditions =
      Vector(
        EvidenceCondition.RootAttachment(requirement, present = false),
        EvidenceCondition.RootAttachment(requirement, present = true)
      )
    )
    assertTrue(
      errors(root.copy(outputRealizations = Vector(realization)))
        .contains(CatalogValidationError.DuplicateRootAttachmentCondition(root.id, realization.id, requirement.keyKind))
    )

  @Test def validatorRejectsEveryMalformedOutputTemplateCategory(): Unit =
    val compiler                                                                       = inventory(snapshot("/templates", 1, Vector.empty))
    val base                                                                           = completeCatalog(compiler)
    val root                                                                           = base.productions.find(_.pattern.prefix == "Root").get
    val self                                                                           = root.effectiveOutputTemplate.composites.head
    def errors(template: LocalOutputCompositeTemplate): Vector[CatalogValidationError] =
      val updated = root.copy(outputTemplate = Some(template))
      val catalog = base.copy(productions = base.productions.map(p => if p.id == root.id then updated else p))
      Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self, self), Map("child" -> Some("self"))))
        .contains(CatalogValidationError.DuplicateOutputId(root.id, "self"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self.copy(parentId = Some("missing"))), Map("child" -> Some("self"))))
        .contains(CatalogValidationError.UnknownOutputParent(root.id, "self", "missing"))
    )
    val cycle             = Vector(self.copy(id = "a", parentId = Some("b")), self.copy(id = "b", parentId = Some("a")))
    assertTrue(
      errors(LocalOutputCompositeTemplate(cycle, Map("child" -> Some("a"))))
        .contains(CatalogValidationError.CyclicOutputParent(root.id, "a"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self), Map.empty))
        .contains(CatalogValidationError.MissingChildMountRole(root.id, "child"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self), Map("child" -> Some("self"), "extra" -> None)))
        .contains(CatalogValidationError.ExtraChildMountRole(root.id, "extra"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self), Map("child" -> Some("missing"))))
        .contains(CatalogValidationError.UnknownChildMountParent(root.id, "child", "missing"))
    )
    val invalidBoundary   = OutputBoundary.Advance(OutputBoundary.ProductionStart(), -1)
    val unsupported       = OutputRangeDeclaration.BoundaryDerived(invalidBoundary, OutputBoundary.ProductionEnd())
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self.copy(range = unsupported)), Map("child" -> Some("self"))))
        .contains(
          CatalogValidationError.InvalidOutputBoundary(root.id, "self", invalidBoundary, "negative boundary advance")
        )
    )
    val emptyDelimiters   = OutputBoundary.EvidenceBoundaryAfterChild(
      "child",
      ChildOccurrenceSelector.First,
      "child",
      ChildOccurrenceSelector.First,
      Vector.empty,
      PositionProvenancePolicy.SourceDerivedOnly
    )
    val delimiterRange    = OutputRangeDeclaration.BoundaryDerived(emptyDelimiters, OutputBoundary.ProductionEnd())
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self.copy(range = delimiterRange)), Map("child" -> Some("self"))))
        .contains(
          CatalogValidationError.InvalidOutputBoundary(
            root.id,
            "self",
            emptyDelimiters,
            "expected delimiters must be nonempty"
          )
        )
    )
    val packageRange      = OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(
      "missing-header",
      Some("missing-body"),
      ClosedSourceLexicalKind.LeftBrace,
      ClosedSourceLexicalKind.RightBrace,
      ClosedSourceLexicalKind.Colon
    )
    val packageErrors     = errors(
      LocalOutputCompositeTemplate(Vector(self.copy(range = packageRange)), Map("child" -> Some("self")))
    )
    assertTrue(
      packageErrors.contains(CatalogValidationError.UnknownOutputRangeChildRole(root.id, "self", "missing-header"))
    )
    assertTrue(
      packageErrors.contains(CatalogValidationError.UnknownOutputRangeChildRole(root.id, "self", "missing-body"))
    )
    val unknownTerminal   = TerminalDeclaration(
      "missing-output-terminal",
      TerminalIntervalSelector.LocalOutput("missing-output"),
      TerminalLeafTarget.Parent,
      OccurrenceCardinality.Optional,
      PsiOutputRoleId.SourceTerminal
    )
    val unknownOutput     = root.copy(terminals = Vector(unknownTerminal))
    val unknownCatalog    =
      base.copy(productions = base.productions.map(p => if p.id == root.id then unknownOutput else p))
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(unknownCatalog, compiler, surfaces(unknownCatalog))
        .contains(CatalogValidationError.UnknownTerminalOutput(root.id, unknownTerminal.id, "missing-output"))
    )
    val siblingRoots      = Vector(self.copy(id = "left"), self.copy(id = "right"))
    assertTrue(
      errors(LocalOutputCompositeTemplate(siblingRoots, Map("child" -> Some("left"))))
        .contains(CatalogValidationError.OverlappingCompilerPositionSiblings(root.id, None, "left", "right"))
    )
    val parentAndSiblings = Vector(
      self.copy(id = "parent"),
      self.copy(id = "left", parentId = Some("parent")),
      self.copy(id = "right", parentId = Some("parent"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(parentAndSiblings, Map("child" -> Some("left"))))
        .contains(
          CatalogValidationError.OverlappingCompilerPositionSiblings(root.id, Some("parent"), "left", "right")
        )
    )
    val sharedAccessor    = AccessorObligation("shared", required = true)
    val wrappers          = Vector(
      self.copy(id = "outer", accessors = Vector(sharedAccessor)),
      self.copy(id = "inner", parentId = Some("outer"), accessors = Vector(sharedAccessor))
    )
    assertFalse(
      errors(LocalOutputCompositeTemplate(wrappers, Map("child" -> Some("inner"))))
        .contains(CatalogValidationError.DuplicateAccessorObligation(root.id, "shared"))
    )

  @Test def validatorAccountsTokenAndPersistenceClaimsAndRejectsIncompleteFacts(): Unit =
    val compiler    = inventory(snapshot("/one", 1, Vector.empty))
    val base        = completeCatalog(compiler)
    val root        = base.productions.head
    val claimedRoot = root.copy(
      terminals = Vector(
        TerminalDeclaration(
          "token",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token("token.surface"),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      persistence = PersistenceObligations.Required(
        "stub.surface",
        "serializer.surface",
        Vector("index.surface"),
        "navigation.surface"
      )
    )
    val catalog     = base.copy(productions = claimedRoot +: base.productions.tail)
    val obligations = Vector(
      ScalaPsiSurfaceRow(
        "token.surface",
        SurfaceFactKind.Token,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "stub.surface",
        SurfaceFactKind.Stub,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "serializer.surface",
        SurfaceFactKind.Serializer,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "index.surface",
        SurfaceFactKind.Index,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "navigation.surface",
        SurfaceFactKind.Navigation,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      )
    )
    val complete    = ScalaPsiSurfaceInventory(surfaces(catalog).rows ++ obligations)
    assertTrue(Scala3PsiProductionCatalogValidator.validate(catalog, compiler, complete).isEmpty)

    val unclaimed = base.copy(productions = root +: base.productions.tail)
    val errors    = Scala3PsiProductionCatalogValidator.validate(unclaimed, compiler, complete)
    obligations.foreach(row => assertTrue(errors.contains(CatalogValidationError.UnaccountedSyntaxSurface(row.id))))

    val incomplete = complete.copy(rows =
      complete.rows :+ ScalaPsiSurfaceRow(
        "incomplete.surface",
        SurfaceFactKind.Element,
        None,
        FactStatus.Unsupported("not constructible"),
        SurfaceClassification.Helper
      )
    )
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, compiler, incomplete)
        .exists(_.isInstanceOf[CatalogValidationError.UnresolvedSurface])
    )

    val neutralRows = Vector(
      ScalaPsiSurfaceRow(
        "helper.surface",
        SurfaceFactKind.Class,
        None,
        FactStatus.Available,
        SurfaceClassification.Helper
      ),
      ScalaPsiSurfaceRow(
        "method.surface",
        SurfaceFactKind.Method,
        None,
        FactStatus.Available,
        SurfaceClassification.Derived
      )
    )
    val wrongKinds  = catalog.copy(productions =
      claimedRoot.copy(
        targetSurfaceId = "helper.surface",
        accessors = Vector(AccessorObligation("method.surface", required = true))
      ) +: catalog.productions.tail
    )
    val kindErrors  = Scala3PsiProductionCatalogValidator.validate(
      wrongKinds,
      compiler,
      ScalaPsiSurfaceInventory(complete.rows ++ neutralRows)
    )
    assertTrue(
      kindErrors.contains(
        CatalogValidationError.InvalidSurface(
          claimedRoot.id,
          claimedRoot.outputRoleId.get,
          "helper.surface",
          SurfaceFactKind.Element
        )
      )
    )
    assertTrue(
      kindErrors.contains(
        CatalogValidationError.InvalidSurface(
          claimedRoot.id,
          claimedRoot.outputRoleId.get,
          "method.surface",
          SurfaceFactKind.PublicAccessor
        )
      )
    )

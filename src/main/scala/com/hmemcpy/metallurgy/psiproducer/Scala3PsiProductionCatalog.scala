package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.CanonicalByteEncoder

private[metallurgy] final case class Scala3PsiProductionCatalog(
    productions: Vector[Scala3PsiProduction],
    stableRoles: StableRoleInventory
)
private[metallurgy] object Scala3PsiProductionCatalog:
  val Empty: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(Vector.empty, StableRoleInventory.Empty)

  private val PersistenceExternalIds =
    TemplatePersistenceSurfaces.ExternalIds ++ DefinitionPersistenceSurfaces.ExternalIds

  def persistenceSchemaFingerprint(catalog: Scala3PsiProductionCatalog): String =
    persistenceSchemaFingerprint(catalog, PersistenceExternalIds)

  private[psiproducer] def persistenceSchemaFingerprint(
      catalog: Scala3PsiProductionCatalog,
      externalIds: Map[PsiOutputRoleId, String]
  ): String =
    val encoder = CanonicalByteEncoder()
    encoder.sequence(catalog.stableRoles.outputRoles.toVector.sortBy(_.value))(role => encoder.string(role.value))
    encoder.sequence(catalog.productions.sortBy(_.id)): production =>
      encoder.string(production.id)
      encoder.string(production.pattern.kind.toString)
      encoder.string(production.pattern.prefix)
      encoder.sequence(production.pattern.fields): field =>
        encoder.string(field.name)
        encoder.string(field.value.toString)
      encoder.sequence(production.pattern.occurrences.sortBy(_.toString))(occurrence =>
        encoder.string(occurrence.toString)
      )
      encoder.sequence(production.pattern.directNodeEvidence.sortBy(_.fieldName)): evidence =>
        encoder.string(evidence.fieldName)
        encoder.string(evidence.sourceClassification.toString)
      encoder.sequence(production.grammarRoleIds.toVector.sortBy(_.value))(role => encoder.string(role.value))
      encoder.sequence(production.children.sortBy(_.roleId)): child =>
        encoder.string(child.roleId)
        encoder.string(child.fieldName)
        encoder.sequence(child.productionIds.toVector.sorted)(encoder.string)
        encoder.string(child.cardinality.toString)
        encoder.string(child.slice.toString)
      encoder.sequence(production.terminals): terminal =>
        encoder.string(terminal.id)
        encoder.string(terminal.selector.toString)
        encoder.string(terminal.target.toString)
        encoder.string(terminal.cardinality.toString)
        encoder.string(terminal.outputRoleId.value)
      encoder.sequence(production.effectiveOutputRealizations.sortBy(_.id)): realization =>
        encoder.string(realization.id)
        encoder.sequence(realization.conditions.sortBy(_.toString))(condition => encoder.string(condition.toString))
        encoder.sequence(realization.evidenceConditions.sortBy(_.toString))(condition =>
          encoder.string(condition.toString)
        )
        encoder.sequence(realization.template.composites): output =>
          encoder.string(output.id)
          encoder.string(output.parentId.getOrElse(""))
          encoder.string(output.range.toString)
          encoder.string(output.outputRoleId.value)
          encoder.string(output.targetSurfaceId)
          encoder.string(externalIds.getOrElse(output.outputRoleId, ""))
          encoder.string(output.targetRequirement.toString)
          encoder.sequence(output.accessors.sortBy(_.toString))(accessor => encoder.string(accessor.toString))
          encoder.string(output.navigation.toString)
          encoder.string(output.ownsStructuralEvidence.toString)
          encoder.string(output.requiresCompilerEndMarker.toString)
          encoder.string(output.realization.toString)
          output.persistence match
            case PersistenceObligations.NotApplicable                                   => encoder.tag(0)
            case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
              encoder.tag(1)
              encoder.string(stub)
              encoder.string(serializer)
              encoder.sequence(indices.sorted)(encoder.string)
              encoder.string(navigation)
        encoder.sequence(realization.template.childMounts.toVector.sortBy(_._1)): (role, parent) =>
          encoder.string(role)
          encoder.string(parent.getOrElse(""))
    CanonicalByteEncoder.sha256Hex(encoder.result())

  lazy val Reviewed: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(
    Scala3PsiPackageImportExportProductions.PackageImportExportPrefixSegment ++
      Scala3PsiAppliedTypeProductions.AppliedTypeSegment ++
      Scala3PsiPackageImportExportProductions.PackageImportExportGivenSegment ++
      Scala3PsiCompoundTypeProductions.CompoundInfixSegment ++
      Scala3PsiPackageImportExportProductions.PackageImportExportStablePathSegment ++
      Scala3PsiTypeAtomProductions.IntegerLiteralSegment ++
      Scala3PsiModifierAnnotationProductions.ModifierAnnotationSegment ++
      Scala3PsiTemplateProductions.TemplateSegment ++
      Scala3PsiDefinitionProductions.DefinitionSegment ++
      Scala3PsiDefinitionPayloadProductions.DefinitionPayloadSegment ++
      Scala3PsiTupleFunctionTypeProductions.TupleFunctionPrefixSegment ++
      Scala3PsiCaptureTypeProductions.CaptureFunctionSegment ++
      Scala3PsiTupleFunctionTypeProductions.TupleFunctionMiddleSegment ++
      Scala3PsiCaptureTypeProductions.CaptureByNameSegment ++
      Scala3PsiTupleFunctionTypeProductions.TupleFunctionSuffixSegment ++
      Scala3PsiCompoundTypeProductions.CompoundTypeSegment ++
      Scala3PsiCaptureTypeProductions.CaptureTypeSegment ++
      Scala3PsiTypeAtomProductions.TypeAtomSegment,
    StableRoleInventory.Reviewed
  )

  def withIntegerLiteralTarget(
      native: Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]],
      compatible: () => Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]]
  ): Either[CatalogCapabilityFailure, Scala3PsiProductionCatalog] =
    val id = Scala3PsiProductionSupport.IntegerLiteralProductionId
    Reviewed.productions.find(_.id == id) match
      case None                                                                                  => Left(CatalogCapabilityFailure.MissingProduction(id))
      case Some(production) if production.targetRequirement != TargetRequirement.NativeCandidate =>
        Left(CatalogCapabilityFailure.InvalidTargetRequirement(id, production.targetRequirement))
      case Some(production)                                                                      =>
        def expectedBehavior(observation: NativeIntegerLiteralObservation): Boolean =
          observation.publicSurfaceId ==
            "org/jetbrains/plugins/scala/lang/psi/api/base/literals/ScIntegerLiteral" &&
            observation.text == observation.contentText &&
            observation.valueClass == "java.lang.Integer" &&
            observation.contentStart == 0 && observation.contentEnd == observation.text.length
        val expectedValues                                                          = Vector("0" -> "0", "42" -> "42", "0x2a" -> "42", "1_000" -> "1000")
        def validBehavior(values: Vector[NativeIntegerLiteralObservation]): Boolean =
          values.map(value => value.text -> value.valueText) == expectedValues && values.forall(expectedBehavior)
        def commonBehavior(observation: NativeIntegerLiteralObservation): Boolean   =
          observation.isSimpleLiteral && observation.literalTypeIdentity &&
            observation.literalType == observation.valueText && observation.widenedType == "Int" &&
            observation.visitorDispatched && observation.visitorElementIdentity && observation.navigationIdentity &&
            observation.validPsi && observation.validContainingFile && observation.validParent &&
            observation.nodePsiIdentity && observation.projectIdentity && observation.exactTextRange &&
            observation.directChildCount == 1 && observation.directChildText == observation.text &&
            observation.integerTokenIdentity && !observation.stubBasedPsi && !observation.stubElementType
        val nativeValid                                                             = native.exists(values =>
          validBehavior(values) && values.forall(observation =>
            observation.implementationSurfaceId == production.targetSurfaceId &&
              observation.elementType == "IntegerLiteral" && observation.isScalaIntegerLiteralElementType &&
              !observation.compatibleElementTypeIdentity && commonBehavior(observation)
          )
        )
        if nativeValid then promote(production, TargetRequirement.Native, production.targetSurfaceId)
        else
          val compatibleResult = compatible()
          val compatibleValid  = compatibleResult.exists(values =>
            validBehavior(values) && values.forall(observation =>
              observation.implementationSurfaceId ==
                "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral" &&
                observation.elementType == "METALLURGY_INTEGER_LITERAL" && !observation.isScalaIntegerLiteralElementType &&
                observation.compatibleElementTypeIdentity && commonBehavior(observation)
            )
          )
          if compatibleValid then
            promote(
              production,
              TargetRequirement.Compatible,
              "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral"
            )
          else Left(CatalogCapabilityFailure.IntegerLiteralTargetsUnavailable(native, compatibleResult))

  private def promote(
      production: Scala3PsiProduction,
      requirement: TargetRequirement,
      targetSurfaceId: String
  ): Either[CatalogCapabilityFailure, Scala3PsiProductionCatalog] =
    Right(
      Reviewed.copy(productions = Reviewed.productions.map:
        case value if value.id == production.id =>
          value.copy(targetSurfaceId = targetSurfaceId, targetRequirement = requirement)
        case value                              => value
      )
    )

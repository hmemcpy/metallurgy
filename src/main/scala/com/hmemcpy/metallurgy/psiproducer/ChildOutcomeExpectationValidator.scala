package com.hmemcpy.metallurgy.psiproducer

private[psiproducer] object ChildOutcomeExpectationValidator:
  def requiredRootsErrors(
      catalog: Scala3PsiProductionCatalog,
      production: Scala3PsiProduction,
      realizationId: String,
      requirements: Vector[RequiredChildRootOutcome]
  ): Vector[CatalogValidationError] =
    requirements.flatMap(requiredRootErrors(catalog, production, realizationId, _))

  def conditionErrors(
      catalog: Scala3PsiProductionCatalog,
      production: Scala3PsiProduction,
      realization: OutputRealization,
      child: ChildDeclaration,
      expected: ChildOutcomeExpectation
  ): Vector[CatalogValidationError] =
    val childProductions = catalog.productions.filter(value => child.productionIds(value.id))
    expected.alternatives.flatMap:
      case ChildOutcomeExpectation.Production(id) if !child.productionIds(id) =>
        Vector(CatalogValidationError.UnknownConditionProductionId(production.id, realization.id, id))
      case ChildOutcomeExpectation.Realization(id)
          if !childProductions.exists(_.effectiveOutputRealizations.exists(_.id == id)) =>
        Vector(CatalogValidationError.UnknownConditionRealizationId(production.id, realization.id, id))
      case ChildOutcomeExpectation.OutputRole(role)
          if !childProductions.exists(
            _.effectiveOutputRealizations.exists(_.template.composites.exists(_.outputRoleId == role))
          ) =>
        Vector(CatalogValidationError.UnknownConditionOutputRole(production.id, realization.id, role))
      case ChildOutcomeExpectation.OutputRoles(roles)                         =>
        roles.toVector
          .filterNot(role =>
            childProductions.exists(
              _.effectiveOutputRealizations.exists(_.template.composites.exists(_.outputRoleId == role))
            )
          )
          .map(role => CatalogValidationError.UnknownConditionOutputRole(production.id, realization.id, role))
      case _                                                                  => Vector.empty

  def requiredRootErrors(
      catalog: Scala3PsiProductionCatalog,
      production: Scala3PsiProduction,
      realizationId: String,
      requirement: RequiredChildRootOutcome
  ): Vector[CatalogValidationError] =
    production.children.find(_.roleId == requirement.roleId) match
      case None        =>
        Vector(
          CatalogValidationError.InvalidChildRootOutcome(
            production.id,
            realizationId,
            requirement.roleId,
            requirement.rootOutcome,
            "unknown child role"
          )
        )
      case Some(child) =>
        val cardinalityErrors =
          val valid = requirement.rootOutcome match
            case ChildRootOutcome.One(_)      => child.cardinality == ChildCardinality.ExactlyOne
            case ChildRootOutcome.All(_)      => child.cardinality.isInstanceOf[ChildCardinality.Repeated]
            case ChildRootOutcome.AnyReviewed => false
          Option
            .unless(valid)(
              CatalogValidationError.InvalidChildRootOutcome(
                production.id,
                realizationId,
                requirement.roleId,
                requirement.rootOutcome,
                "outcome does not match child cardinality"
              )
            )
            .toVector
        val expectationErrors = requirement.rootOutcome match
          case ChildRootOutcome.One(expected) =>
            rootErrors(
              catalog,
              production,
              realizationId,
              requirement.roleId,
              requirement.rootOutcome,
              child,
              expected
            )
          case ChildRootOutcome.All(expected) =>
            rootErrors(
              catalog,
              production,
              realizationId,
              requirement.roleId,
              requirement.rootOutcome,
              child,
              expected
            )
          case ChildRootOutcome.AnyReviewed   => Vector.empty
        cardinalityErrors ++ expectationErrors

  def rootErrors(
      catalog: Scala3PsiProductionCatalog,
      production: Scala3PsiProduction,
      realizationId: String,
      roleId: String,
      rootOutcome: ChildRootOutcome,
      child: ChildDeclaration,
      expected: ChildOutcomeExpectation
  ): Vector[CatalogValidationError] =
    val childProductions = catalog.productions.filter(value => child.productionIds(value.id))
    expected.alternatives.flatMap: alternative =>
      val reason = alternative match
        case ChildOutcomeExpectation.Production(id) if !child.productionIds(id) =>
          Some("unknown child production")
        case ChildOutcomeExpectation.Realization(id)
            if !childProductions.exists(_.effectiveOutputRealizations.exists(_.id == id)) =>
          Some("unknown child realization")
        case ChildOutcomeExpectation.OutputRole(role)
            if !childProductions.exists(
              _.effectiveOutputRealizations.exists(
                _.template.composites.exists(output => output.parentId.isEmpty && output.outputRoleId == role)
              )
            ) =>
          Some("unknown child root output role")
        case ChildOutcomeExpectation.OutputRoles(roles)
            if roles.isEmpty || !roles.forall(role =>
              childProductions.exists(
                _.effectiveOutputRealizations.exists(
                  _.template.composites.exists(output => output.parentId.isEmpty && output.outputRoleId == role)
                )
              )
            ) =>
          Some("unknown child root output role set")
        case _                                                                  => None
      reason.map(value =>
        CatalogValidationError.InvalidChildRootOutcome(
          production.id,
          realizationId,
          roleId,
          rootOutcome,
          value
        )
      )

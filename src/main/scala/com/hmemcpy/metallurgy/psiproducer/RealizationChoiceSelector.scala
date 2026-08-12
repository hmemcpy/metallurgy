package com.hmemcpy.metallurgy.psiproducer

private[metallurgy] enum RealizationChoiceFailure:
  case InvalidDeclaration(productionId: String, reason: String)
  case CandidateDefect(productionId: String, realizationId: String, defect: CandidateRealizationDefect)

private[metallurgy] enum CandidateRealizationDefect:
  case CandidateEvidence(reason: String)
  case ChildRootAmbiguity(roleId: String, child: ProductionInstanceId, actual: Int)
  case Binding(reason: String)
  case SourceOwnership(reason: String)

private[metallurgy] enum CandidateInapplicability:
  case ExcludedTypeApplication
  case ExcludedRootAttachment(attachment: AttachmentEvidence)
  case MissingChildRoot(roleId: String, child: ProductionInstanceId, productionId: String, realizationId: String)
  case UnsupportedChildRoot(roleId: String, child: ProductionInstanceId, outputRoleId: PsiOutputRoleId)

private[metallurgy] enum RealizationSelectionReason:
  case Ordinary
  case PreferredCandidate
  case CompleteFallback(reviewedReasons: Vector[CandidateInapplicability])

private[metallurgy] final case class SelectedRealization(
    realization: OutputRealization,
    reason: RealizationSelectionReason
)

private[metallurgy] enum ProductionMatchRetentionFailure:
  case Missing
  case Ambiguous(productionIds: Vector[String])

private[metallurgy] final case class RetainedProductionMatch(
    candidate: Scala3PsiProduction,
    fallback: Option[Scala3PsiProduction]
)

private[metallurgy] object ProductionMatchRetention:
  def retain(
      catalog: Scala3PsiProductionCatalog,
      matches: Vector[Scala3PsiProduction]
  ): Either[ProductionMatchRetentionFailure, RetainedProductionMatch] = matches match
    case Vector()      => Left(ProductionMatchRetentionFailure.Missing)
    case Vector(value) => Right(RetainedProductionMatch(value, None))
    case values        =>
      val byId = values.map(value => value.id -> value).toMap
      catalog.productionAlternatives.filter(alternative =>
        byId.keySet == Set(alternative.candidateId, alternative.fallbackId)
      ) match
        case Vector(alternative) =>
          Right(RetainedProductionMatch(byId(alternative.candidateId), Some(byId(alternative.fallbackId))))
        case _                   => Left(ProductionMatchRetentionFailure.Ambiguous(byId.keys.toVector.sorted))

private[metallurgy] object RealizationChoiceSelector:
  def select(
      production: Scala3PsiProduction,
      ordinaryMatches: Vector[OutputRealization],
      candidateAssessment: OutputRealization => Either[CandidateRealizationDefect, Vector[CandidateInapplicability]]
  ): Either[RealizationChoiceFailure, SelectedRealization] =
    production.realizationChoice match
      case None         => selectOrdinary(production, ordinaryMatches)
      case Some(choice) => selectDeclared(production, choice, candidateAssessment)

  private def selectOrdinary(
      production: Scala3PsiProduction,
      matches: Vector[OutputRealization]
  ): Either[RealizationChoiceFailure, SelectedRealization] =
    matches match
      case Vector(value) => Right(SelectedRealization(value, RealizationSelectionReason.Ordinary))
      case Vector()      =>
        Left(RealizationChoiceFailure.InvalidDeclaration(production.id, "no output realization matches"))
      case values        =>
        Left(
          RealizationChoiceFailure.InvalidDeclaration(
            production.id,
            s"output realizations are ambiguous: ${values.map(_.id).sorted.mkString(", ")}"
          )
        )

  private def selectDeclared(
      production: Scala3PsiProduction,
      choice: RealizationChoice,
      candidateAssessment: OutputRealization => Either[CandidateRealizationDefect, Vector[CandidateInapplicability]]
  ): Either[RealizationChoiceFailure, SelectedRealization] =
    val byId = production.effectiveOutputRealizations.groupBy(_.id)
    (byId.get(choice.candidateId), byId.get(choice.fallbackId)) match
      case (Some(Vector(candidate)), Some(Vector(fallback))) if byId.size == 2 =>
        candidateAssessment(candidate).left
          .map(reason => RealizationChoiceFailure.CandidateDefect(production.id, candidate.id, reason))
          .map:
            case Vector() => SelectedRealization(candidate, RealizationSelectionReason.PreferredCandidate)
            case reasons  => SelectedRealization(fallback, RealizationSelectionReason.CompleteFallback(reasons))
      case _                                                                   =>
        Left(
          RealizationChoiceFailure.InvalidDeclaration(
            production.id,
            "declared choice must name exactly one candidate and one fallback and no other realizations"
          )
        )

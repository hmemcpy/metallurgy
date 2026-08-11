package com.hmemcpy.metallurgy.psiproducer

private[metallurgy] object Scala3PsiProductionCoverageReport:
  def markdown(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): String =
    val effectiveSurfaces = surfaces.withCatalogCapabilities(catalog)
    val lines             = Vector.newBuilder[String]
    val validation        = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, effectiveSurfaces)
    lines += "# Scala 3 PSI production coverage"
    lines += ""
    lines += s"- Compiler: `${compiler.identity.coordinate.organization}:${compiler.identity.coordinate.artifact}:${compiler.identity.coordinate.version}`"
    lines += s"- Compiler inventory: `${compiler.fingerprint}`"
    lines += s"- Scala PSI inventory: `${effectiveSurfaces.fingerprint}`"
    lines += s"- Reviewed productions: ${catalog.productions.size}"
    lines += s"- Validation: **${if validation.isEmpty then "complete" else "incomplete"}**"
    validation
      .groupMapReduce(_.productPrefix)(_ => 1)(_ + _)
      .toVector
      .sortBy(_._1)
      .foreach((name, count) => lines += s"- Outstanding `$name`: $count")
    lines += ""
    lines += "## Stable role inventory"
    lines += ""
    lines += "### Grammar roles"
    lines += ""
    catalog.stableRoles.grammarRoles.toVector
      .sortBy(_.value)
      .foreach: role =>
        val alternatives = catalog.productions.filter(_.grammarRoleIds(role)).map(_.id).distinct.sorted
        val status       =
          if alternatives.isEmpty then "unreferenced" else s"catalog-alternatives=${alternatives.mkString(",")}"
        lines += s"- `${role.value}` — **$status**"
    lines += ""
    lines += "### Output roles"
    lines += ""
    catalog.stableRoles.outputRoles.toVector
      .sortBy(_.value)
      .foreach: role =>
        val contracts = catalog.productions.flatMap: production =>
          val terminals = production.terminals.collect:
            case terminal if terminal.outputRoleId == role =>
              val target = terminal.target match
                case TerminalLeafTarget.Token(surfaceId, _) => s"->$surfaceId"
                case _                                      => ""
              s"${production.id}:terminal:${terminal.id}$target"
          val outputs   = production.effectiveOutputRealizations.flatMap: realization =>
            realization.template.composites.collect:
              case output if output.outputRoleId == role =>
                s"${production.id}:${realization.id}:${output.id}->${output.targetSurfaceId}"
          terminals ++ outputs
        val status    =
          if contracts.isEmpty then "unreferenced" else s"contracts=${contracts.distinct.sorted.mkString(",")}"
        lines += s"- `${role.value}` — **$status**"
    lines += ""
    lines += "## Compiler productions"
    lines += ""
    compiler.productions
      .sortBy(row => (row.kind.toString, row.prefix, row.fields.map(_.toString).mkString("\u0000")))
      .foreach: row =>
        val fields = row.fields.map(field => s"${field.name}:${render(field.value)}").mkString(", ")
        lines += s"### `${row.kind}.${row.prefix}`"
        lines += ""
        lines += s"- Fields: `$fields`"
        row.occurrences
          .sortBy(render)
          .foreach: occurrence =>
            val selected = CatalogShapeMatcher.selectAggregated(catalog, row, occurrence)
            val status   = selected match
              case Vector(production) =>
                val outputs      = production.effectiveOutputRealizations.flatMap(_.template.composites)
                val terminals    = production.terminals
                val requirements = outputs
                  .map(_.targetRequirement.toString)
                  .distinct
                  .sorted
                val outputRoles  = (outputs.map(_.outputRoleId) ++ terminals.map(_.outputRoleId))
                  .map(_.value)
                  .distinct
                  .sorted
                val targets      = (outputs.map(_.targetSurfaceId) ++ terminals.collect:
                  case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surfaceId, _), _, _, _) => surfaceId
                ).distinct.sorted
                val providers    = if requirements.isEmpty then "transparent" else requirements.mkString(",")
                val boundary     = missingBoundary(production, validation)
                s"mapped; grammar-role=${production.grammarRoleId.value}; catalog-alternative=${production.id}; compiler-shape=${row.kind}.${row.prefix}; compiler-context=${render(occurrence)}; output-roles=${renderList(outputRoles)}; host-targets=${renderList(targets)}; providers=$providers; missing-boundary=$boundary"
              case Vector()           =>
                s"unmapped; compiler-shape=${row.kind}.${row.prefix}; compiler-context=${render(occurrence)}; missing-boundary=bridge-normalization-or-neutral-grammar-role"
              case productions        =>
                s"ambiguous; compiler-shape=${row.kind}.${row.prefix}; compiler-context=${render(occurrence)}; catalog-alternatives=${productions.map(_.id).sorted.mkString(",")}; missing-boundary=neutral-grammar-role-selection"
            lines += s"- `${render(occurrence)}` — **$status**"
        lines += ""
    lines += "## Scala PSI surfaces"
    lines += ""
    val references        = catalog.productions
      .flatMap: production =>
        val terminals = production.terminals.collect:
          case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id, _), _, _, _) => id
        val outputs   = production.effectiveOutputRealizations
          .flatMap(_.template.composites)
          .flatMap: output =>
            val persistence = output.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(stub, serializer, navigation) ++ indices
            Vector(output.targetSurfaceId) ++ output.accessors.map(_.surfaceId) ++ persistence
        (outputs ++ terminals)
          .map(_ -> production.id)
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct.sorted)
      .toMap
    effectiveSurfaces.rows
      .sortBy(row => (row.kind.toString, row.id))
      .foreach: row =>
        val status = references.get(row.id) match
          case Some(productions)                                                  => s"catalog-referenced:${productions.mkString(",")}"
          case None if row.classification == SurfaceClassification.SyntaxContract =>
            s"unmapped:${row.classification}:missing-boundary=stable-output-role-or-compatibility-binding"
          case None                                                               => s"unmapped:${row.classification}"
        lines += s"- `${row.kind}:${row.id}` — **${row.status}:$status**"
    val persisted         = Scala3PsiProductionCatalog.persistedSchemaStructure(
      catalog,
      Scala3DotcFileElementType.SchemaVersion,
      Scala3DotcFileElementType.ExternalId
    )
    val plan              = Scala3PsiProductionCatalog.catalogPlanStructure(catalog)
    lines += ""
    lines += "## Persisted schema structure"
    lines += ""
    persisted.rows.foreach(row => lines += s"- `$row`")
    lines += ""
    lines += "## Catalog and plan structure"
    lines += ""
    plan.rows.foreach(row => lines += s"- `$row`")
    lines += ""
    lines += "## Structural fingerprints"
    lines += ""
    lines += s"- Persistence schema: `${persisted.fingerprint}`"
    lines += s"- Catalog and plan: `${plan.fingerprint}`"
    lines.result().mkString("\n") + "\n"

  private def missingBoundary(
      production: Scala3PsiProduction,
      validation: Vector[CatalogValidationError]
  ): String =
    if validation.exists:
        case CatalogValidationError.UnknownGrammarRole(id, _) if id == production.id                   => true
        case CatalogValidationError.CatalogAlternativeDerivedGrammarRole(id, _) if id == production.id => true
        case CatalogValidationError.CompilerDerivedGrammarRole(id, _, _) if id == production.id        => true
        case _                                                                                         => false
    then "neutral-grammar-role"
    else if validation.exists:
        case CatalogValidationError.MissingDefaultOutputRole(id) if id == production.id       => true
        case CatalogValidationError.UnknownOutputRole(id, _, _) if id == production.id        => true
        case CatalogValidationError.HostDerivedOutputRole(id, _, _, _) if id == production.id => true
        case _                                                                                => false
    then "stable-output-role"
    else if production.effectiveOutputRealizations
        .flatMap(_.template.composites)
        .exists(
          _.targetRequirement == TargetRequirement.NativeCandidate
        ) || validation.exists:
        case CatalogValidationError.InvalidSurface(id, _, _, _) if id == production.id          => true
        case CatalogValidationError.InvalidSurfaceOwner(id, _, _, _) if id == production.id     => true
        case CatalogValidationError.IncompleteSurfaceStatus(id, _, _, _) if id == production.id => true
        case _                                                                                  => false
    then "compatibility-binding"
    else "none"

  private def renderList(values: Vector[String]): String =
    if values.isEmpty then "none" else values.mkString(",")

  private def render(occurrence: CompilerProductionContext): String =
    val context = occurrence.context match
      case None        => "root"
      case Some(value) =>
        val owner     = s"${value.ownerKind}.${value.ownerPrefix}/${renderPath(value.path)}"
        val ancestors = value.ancestors
          .map(ancestor => s"${ancestor.ownerKind}.${ancestor.ownerPrefix}/${renderPath(ancestor.path)}")
          .mkString(">")
        if ancestors.isEmpty then owner else s"$owner@[$ancestors]"
    s"$context:${occurrence.sourceClassification}"

  private def renderPath(path: Vector[CatalogPathSegment]): String = path
    .map:
      case CatalogPathSegment.NamedField(name)        => name
      case CatalogPathSegment.Optional                => "?"
      case CatalogPathSegment.RepeatedElement         => "*"
      case CatalogPathSegment.NestedProduct(producer) => s"product($producer)"
    .mkString("/")

  private def render(pattern: CatalogValuePattern): String = pattern match
    case CatalogValuePattern.Node                                   => "Node"
    case CatalogValuePattern.NodePrefix(prefix)                     => s"Node[$prefix]"
    case CatalogValuePattern.NodeExceptPrefix(prefix)               => s"Node[!$prefix]"
    case CatalogValuePattern.Positioned                             => "Positioned"
    case CatalogValuePattern.Optional(value)                        => s"Optional[${render(value)}]"
    case CatalogValuePattern.EmptyOptional(value)                   => s"EmptyOptional[${render(value)}]"
    case CatalogValuePattern.Repeated(value)                        => s"Repeated[${render(value)}]"
    case CatalogValuePattern.NonEmptyRepeated(value)                => s"NonEmptyRepeated[${render(value)}]"
    case CatalogValuePattern.EmptyRepeated(value)                   => s"EmptyRepeated[${render(value)}]"
    case CatalogValuePattern.LeadingThenRepeated(leading, trailing) =>
      s"LeadingThenRepeated[${render(leading)},${render(trailing)}]"
    case CatalogValuePattern.AnyOf(values)                          => s"AnyOf[${values.map(render).mkString(",")}]"
    case CatalogValuePattern.Product(prefix, fields)                =>
      s"$prefix(${fields.map(field => s"${field.name}:${render(field.value)}").mkString(",")})"
    case CatalogValuePattern.Name                                   => "Name"
    case CatalogValuePattern.GeneratedName                          => "GeneratedName"
    case CatalogValuePattern.ClassifiedName(value)                  => s"Name[$value]"
    case CatalogValuePattern.LowercaseName                          => "LowercaseName"
    case CatalogValuePattern.NonLowercaseName                       => "NonLowercaseName"
    case CatalogValuePattern.BacktickedName                         => "BacktickedName"
    case CatalogValuePattern.Scalar(kind)                           => s"Scalar[$kind]"
    case CatalogValuePattern.ExactScalar(kind, value)               => s"ExactScalar[$kind,$value]"
    case CatalogValuePattern.Unsupported(runtimeType)               => s"Unsupported[$runtimeType]"

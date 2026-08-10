package com.hmemcpy.metallurgy.psiproducer

private[metallurgy] final class PreparedProductionCatalog private (
    val catalog: Scala3PsiProductionCatalog,
    val compiler: AggregatedCompilerProductionInventory,
    val surfaces: ScalaPsiSurfaceInventory
)

private[metallurgy] object PreparedProductionCatalog:
  def prepare(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Either[Vector[CatalogValidationError], PreparedProductionCatalog] =
    val errors = Scala3PsiProductionCatalogValidator.validateExecutable(catalog, compiler, surfaces) ++
      compiler.scenarios.flatMap(RuntimeRealizationSelector.validate(catalog, _))
    Either.cond(errors.isEmpty, new PreparedProductionCatalog(catalog, compiler, surfaces), errors)

  def prepareRuntimeSubset(
      catalog: Scala3PsiProductionCatalog,
      runtime: CompilerRuntimeInventory,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Either[Vector[CatalogValidationError], PreparedProductionCatalog] =
    val errors = Scala3PsiProductionCatalogValidator.validateExecutable(catalog, runtime, surfaces) ++
      RuntimeRealizationSelector.validate(catalog, runtime)
    Either.cond(errors.isEmpty, new PreparedProductionCatalog(catalog, compiler, surfaces), errors.distinct)

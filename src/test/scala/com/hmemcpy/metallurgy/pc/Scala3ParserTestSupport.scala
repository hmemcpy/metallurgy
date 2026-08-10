package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  FactStatus,
  ImportPersistenceSurfaces,
  PersistenceObligations,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  ScalaPsiSurfaceRow,
  SurfaceFactKind,
  SurfaceClassification,
  TerminalDeclaration,
  TerminalLeafTarget
}

import java.nio.file.Path

private[pc] abstract class Scala3ParserTestSupport:

  private val ScalaVersion = "3.7.4"

  private def compilerDistribution(): Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve(ScalaVersion)
      .fold(error => throw error.toException, identity)

  protected final def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri
            .from(uri)
            .fold(message => throw new AssertionError(message), identity),
          source,
          Vector.empty
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  protected final def openBridge(): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion),
        compilerDistribution().map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  protected final def withImportTokenSurfaces(inventory: ScalaPsiSurfaceInventory): ScalaPsiSurfaceInventory =
    val catalog = Scala3PsiProductionCatalog.Reviewed
    val tokens  = catalog.productions.flatMap(_.terminals.collect {
      case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surfaceId, _), _, _, _) => surfaceId
    })
    val indices = catalog.productions.flatMap(
      _.effectiveOutputRealizations
        .flatMap(_.template.composites)
        .flatMap(_.persistence match
          case PersistenceObligations.Required(_, _, values, _) => values
          case PersistenceObligations.NotApplicable             => Vector.empty
        )
    )
    inventory.copy(rows =
      inventory.rows ++ tokens.distinct.map(id =>
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
      ) ++ indices.distinct.map(id =>
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Index,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
      ) :+
        ScalaPsiSurfaceRow(
          ImportPersistenceSurfaces.SelfNavigation,
          SurfaceFactKind.Navigation,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
    )

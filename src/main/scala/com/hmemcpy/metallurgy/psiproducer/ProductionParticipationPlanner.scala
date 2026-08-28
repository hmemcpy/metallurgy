package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import scala.util.boundary
import scala.util.boundary.break

private[metallurgy] enum ProductionParticipationFailure:
  case UnknownChildRole(parent: ProductionInstanceId, realizationId: String, roleId: String)
  case ScalarRootOutcomeMisuse(parent: ProductionInstanceId, realizationId: String, roleId: String)
  case RepeatedRootOutcomeMisuse(parent: ProductionInstanceId, realizationId: String, roleId: String)
  case ChildRootCount(
      parent: ProductionInstanceId,
      realizationId: String,
      roleId: String,
      child: ProductionInstanceId,
      actual: Int
  )
  case InterruptedClosure(parent: ProductionInstanceId, child: ProductionInstanceId)
  case CyclicClosure(path: Vector[ProductionInstanceId])
  case SharedClosureNode(
      parent: ProductionInstanceId,
      child: ProductionInstanceId,
      owners: Vector[ProductionInstanceId]
  )
  case MultiplyAbsorbedNode(child: ProductionInstanceId, parents: Vector[ProductionInstanceId])
  case ChildOutsideParent(parent: ProductionInstanceId, child: ProductionInstanceId)
  case UnpositionedClosureMember(parent: ProductionInstanceId, child: ProductionInstanceId)
  case ParentHasNoSourceRange(parent: ProductionInstanceId)
  case PartiallyAbsorbedSourceClaim(kind: InventoryKind, valueId: Long, occurrences: Vector[ProductionOccurrenceId])

private[metallurgy] final case class ProductionParticipation(
    retained: Vector[ProductionInstanceId],
    absorbedBy: Map[ProductionInstanceId, ProductionInstanceId],
    absorptions: Vector[PlannedChildClosureAbsorption]
):
  def transferredOwner(
      claim: SourceClaim
  ): Either[ProductionParticipationFailure, Option[ProductionInstanceId]] =
    val (kind, valueId, occurrences) = claim match
      case SourceClaim.Node(id, values)       =>
        (InventoryKind.Node, id, values.map(value => ProductionOccurrenceId(value.ownerNodeId, value.fieldPath)))
      case SourceClaim.Positioned(id, values) =>
        (InventoryKind.Positioned, id, values.map(value => ProductionOccurrenceId(value.ownerNodeId, value.fieldPath)))
      case SourceClaim.Diagnostic(_)          => return Right(None)
    val occurrenceOwners             =
      occurrences.map(occurrence => absorbedBy.get(ProductionInstanceId(kind, valueId, Some(occurrence))))
    occurrenceOwners.flatten.distinct match
      case Vector()                                                    => Right(None)
      case Vector(owner) if occurrenceOwners.forall(_.contains(owner)) => Right(Some(owner))
      case _                                                           =>
        Left(
          ProductionParticipationFailure.PartiallyAbsorbedSourceClaim(kind, valueId, occurrences)
        )

private[metallurgy] object ProductionParticipationPlanner:
  def plan(
      active: Vector[ProductionInstanceId],
      selected: collection.Map[ProductionInstanceId, Scala3PsiProduction],
      children: collection.Map[
        ProductionInstanceId,
        Vector[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]
      ],
      realizations: collection.Map[ProductionInstanceId, OutputRealization],
      position: ProductionInstanceId => ParserNodePosition
  ): Either[ProductionParticipationFailure, ProductionParticipation] =
    val activeSet = active.toSet
    val parents   = children.iterator
      .flatMap((parent, values) => values.map((_, _, child) => child -> parent))
      .toVector
      .groupMap(_._1)(_._2)

    def matches(
        child: ProductionInstanceId,
        expected: ChildOutcomeExpectation,
        root: OutputCompositeDeclaration
    ): Boolean = expected.alternatives.exists:
      case ChildOutcomeExpectation.Production(id)     => selected(child).id == id
      case ChildOutcomeExpectation.Realization(id)    => realizations(child).id == id
      case ChildOutcomeExpectation.OutputRole(role)   => root.outputRoleId == role
      case ChildOutcomeExpectation.OutputRoles(roles) => roles(root.outputRoleId)
      case ChildOutcomeExpectation.AnyOf(_)           => false

    def roots(
        parent: ProductionInstanceId,
        realization: OutputRealization,
        roleId: String,
        child: ProductionInstanceId,
        expected: ChildOutcomeExpectation
    ): Either[ProductionParticipationFailure, Unit] =
      val localRoots = realizations(child).template.composites.filter(_.parentId.isEmpty)
      val matching   = localRoots.filter(matches(child, expected, _))
      Either.cond(
        localRoots.size == 1 && matching.size == 1,
        (),
        ProductionParticipationFailure.ChildRootCount(parent, realization.id, roleId, child, localRoots.size)
      )

    def closure(
        parent: ProductionInstanceId,
        root: ProductionInstanceId
    ): Either[ProductionParticipationFailure, Vector[ProductionInstanceId]] =
      val result = Vector.newBuilder[ProductionInstanceId]
      val done   = collection.mutable.HashSet.empty[ProductionInstanceId]
      val stack  = collection.mutable.Stack((root, false, Vector.empty[ProductionInstanceId]))
      while stack.nonEmpty do
        val (current, exiting, path) = stack.pop()
        if !activeSet(current) then return Left(ProductionParticipationFailure.InterruptedClosure(parent, current))
        if exiting then done += current
        else if path.contains(current) then return Left(ProductionParticipationFailure.CyclicClosure(path :+ current))
        else if !done(current) then
          result += current
          stack.push((current, true, path))
          children
            .getOrElse(current, Vector.empty)
            .reverseIterator
            .foreach((_, _, child) => stack.push((child, false, path :+ current)))
      Right(result.result())

    boundary[Either[ProductionParticipationFailure, ProductionParticipation]]:
      val absorbedBy  = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, ProductionInstanceId]
      val absorptions = Vector.newBuilder[PlannedChildClosureAbsorption]
      active.foreach: parent =>
        val production  = selected(parent)
        val realization = realizations(parent)
        if realization.childClosureAbsorptions.nonEmpty then
          absorbedBy
            .get(parent)
            .foreach(other =>
              break(Left(ProductionParticipationFailure.MultiplyAbsorbedNode(parent, Vector(other, parent))))
            )
        realization.childClosureAbsorptions.foreach: absorption =>
          production.children.find(_.roleId == absorption.roleId) match
            case None              =>
              break(
                Left(ProductionParticipationFailure.UnknownChildRole(parent, realization.id, absorption.roleId))
              )
            case Some(declaration) =>
              val roleChildren     = children
                .getOrElse(parent, Vector.empty)
                .collect { case (roleId, _, child) if roleId == absorption.roleId => child }
              absorption.rootOutcome match
                case ChildRootOutcome.One(_)      =>
                  declaration.cardinality match
                    case ChildCardinality.ExactlyOne => ()
                    case _                           =>
                      break(
                        Left(
                          ProductionParticipationFailure.ScalarRootOutcomeMisuse(
                            parent,
                            realization.id,
                            absorption.roleId
                          )
                        )
                      )
                  if roleChildren.size != 1 then
                    break(
                      Left(
                        ProductionParticipationFailure.ChildRootCount(
                          parent,
                          realization.id,
                          absorption.roleId,
                          parent,
                          roleChildren.size
                        )
                      )
                    )
                case ChildRootOutcome.All(_)      =>
                  declaration.cardinality match
                    case ChildCardinality.Repeated(_, _) => ()
                    case _                               =>
                      break(
                        Left(
                          ProductionParticipationFailure.RepeatedRootOutcomeMisuse(
                            parent,
                            realization.id,
                            absorption.roleId
                          )
                        )
                      )
                case ChildRootOutcome.AnyReviewed => ()
              roleChildren.foreach: child =>
                absorption.rootOutcome match
                  case ChildRootOutcome.One(expected) =>
                    roots(parent, realization, absorption.roleId, child, expected) match
                      case Left(failure) => break(Left(failure))
                      case Right(_)      => ()
                  case ChildRootOutcome.All(expected) =>
                    roots(parent, realization, absorption.roleId, child, expected) match
                      case Left(failure) => break(Left(failure))
                      case Right(_)      => ()
                  case ChildRootOutcome.AnyReviewed   =>
                    val rootCount = realizations(child).template.composites.count(_.parentId.isEmpty)
                    if rootCount > 1 then
                      break(
                        Left(
                          ProductionParticipationFailure.ChildRootCount(
                            parent,
                            realization.id,
                            absorption.roleId,
                            child,
                            rootCount
                          )
                        )
                      )
              val absorbedChildren = roleChildren.filterNot: child =>
                val roots = realizations(child).template.composites.filter(_.parentId.isEmpty)
                roots.size == 1 && absorption.retainedRootRoles(roots.head.outputRoleId)
              val closures         = absorbedChildren.map(child => closure(parent, child))
              closures.collectFirst { case Left(failure) => failure } match
                case Some(failure) => break(Left(failure))
                case None          => ()
              val values           = closures.collect { case Right(value) => value }.flatten.distinct
              val valueSet         = values.toSet
              values.foreach: child =>
                val externalOwners = parents
                  .getOrElse(child, Vector.empty)
                  .filterNot(owner => valueSet(owner) || (roleChildren.contains(child) && owner == parent))
                if externalOwners.nonEmpty then
                  break(
                    Left(
                      ProductionParticipationFailure.SharedClosureNode(parent, child, externalOwners.distinct)
                    )
                  )
                absorbedBy.get(child) match
                  case Some(other) =>
                    break(
                      Left(ProductionParticipationFailure.MultiplyAbsorbedNode(child, Vector(other, parent)))
                    )
                  case None        => absorbedBy += child -> parent
              val parentRange      = position(parent) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => range
                case _                                                                               =>
                  break(Left(ProductionParticipationFailure.ParentHasNoSourceRange(parent)))
              values.foreach: child =>
                position(child) match
                  case ParserNodePosition.Positioned(range, _, _)
                      if range.startOffset < parentRange.startOffset || range.endOffset > parentRange.endOffset =>
                    break(Left(ProductionParticipationFailure.ChildOutsideParent(parent, child)))
                  case _: ParserNodePosition.Positioned => ()
                  case ParserNodePosition.Absent        =>
                    break(Left(ProductionParticipationFailure.UnpositionedClosureMember(parent, child)))
              absorptions += PlannedChildClosureAbsorption(
                parent,
                realization.id,
                absorption.roleId,
                absorbedChildren,
                values,
                parentRange
              )

      Right(
        ProductionParticipation(
          active.filterNot(absorbedBy.contains),
          absorbedBy.toMap,
          absorptions.result()
        )
      )

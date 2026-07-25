package dogfood.triage.givenusing

import scala.compiletime.deferred

trait ResultOps[T]:
  def emptyResult: T

trait ProcessCompiled[R]:
  given ops: ResultOps[R] = deferred

class Box3D

def boundingBox(): Unit =
  given resultOps: ResultOps[Box3D] with
    override def emptyResult: Box3D = new Box3D
  object AaBb extends ProcessCompiled[Box3D]

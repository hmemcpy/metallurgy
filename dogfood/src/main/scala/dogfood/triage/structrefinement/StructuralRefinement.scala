package dogfood.triage.structrefinement

import scala.reflect.Selectable.reflectiveSelectable

val api: { val x: Int } = new:
  val x: Int = 42

val v: Int = api.x

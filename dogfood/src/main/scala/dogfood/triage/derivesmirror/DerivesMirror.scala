package dogfood.triage.derivesmirror

import scala.deriving.Mirror

case class Person(name: String, age: Int)

val mirror = summon[Mirror.Of[Person]]
val labels: mirror.MirroredElemLabels = ???

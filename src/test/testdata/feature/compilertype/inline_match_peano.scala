sealed trait Nat
case object Zero extends Nat
case class Succ[N <: Nat](n: N) extends Nat

transparent inline def toInt[N <: Nat]: Int = inline scala.compiletime.erasedValue[N] match
  case _: Zero.type => 0
  case _: Succ[n]  => toInt[n] + 1

val natTwo: Int = toInt[Succ[Succ[Zero.type]]]

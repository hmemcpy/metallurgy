trait Nat
case object Zero extends Nat
case class Succ[N <: Nat](n: N) extends Nat

transparent inline def toInt(n: Nat): Int =
  inline n match
    case Zero     => 0
    case Succ(n1) => toInt(n1) + 1

inline val natTwo = toInt(Succ(Succ(Zero)))
val intTwo: 2 = natTwo

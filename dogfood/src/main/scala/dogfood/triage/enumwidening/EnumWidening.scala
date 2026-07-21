package dogfood.triage.enumwidening

enum E:
  case A, B

val xs = List(E.A, E.B)
val head: E = xs.head

package dogfood.triage.exportclause

object B:
  object F:
    def x: String = ""
  trait F
  object D

object S:
  export B.{ F, D }

import S.F.x

val v: String = x

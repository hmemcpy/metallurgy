package dogfood.triage.matchtypeops

import scala.compiletime.ops.int.*

type Double[N <: Int] = N * 2

val x: Double[3] = 6

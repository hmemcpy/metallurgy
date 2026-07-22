package com.hmemcpy.metallurgy.compilerbackend

import org.jetbrains.plugins.scala.lang.psi.types.result.TypeResult

private[metallurgy] enum CompilerBackendState:
  case Current(renderedType: String, result: TypeResult)
  case Pending
  case Unavailable
  case Failed

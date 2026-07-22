package com.hmemcpy.metallurgy.compilerbackend

private[metallurgy] enum CompilerBackendCommit:
  case Committed(invalidatedElementCount: Int)
  case Rejected

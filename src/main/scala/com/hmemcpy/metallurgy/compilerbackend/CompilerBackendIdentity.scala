package com.hmemcpy.metallurgy.compilerbackend

private[metallurgy] enum CompilerBackendIdentity:
  case Direct
  case Snapshot(generation: CompilerBackendGeneration)

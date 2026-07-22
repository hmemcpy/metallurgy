package com.hmemcpy.metallurgy.compilerbackend

private[metallurgy] enum CompilerBackendShimStatus:
  case Enabled(transformedRoots: Int, unavailableRoots: Vector[String])
  case Disabled(reason: String)

  def isEnabled: Boolean = this match
    case Enabled(_, _) => true
    case Disabled(_)   => false

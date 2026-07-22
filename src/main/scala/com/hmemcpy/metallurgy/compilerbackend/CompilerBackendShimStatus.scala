package com.hmemcpy.metallurgy.compilerbackend

private[metallurgy] enum CompilerBackendShimStatus:
  case Enabled(classFingerprint: String, methodFingerprint: String)
  case Disabled(reason: String)

  def isEnabled: Boolean = this match
    case Enabled(_, _) => true
    case Disabled(_)   => false

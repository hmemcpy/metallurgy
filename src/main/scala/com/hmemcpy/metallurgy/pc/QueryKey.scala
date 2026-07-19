package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.util.TextRange

private[metallurgy] enum QueryKey:
  case TypeAt(range: TextRange)
  case Complete(offset: Int)
  case Hover(offset: Int)
  case Diagnose(range: TextRange)
  case SemanticTokens

package com.hmemcpy.metallurgy.pc

private[metallurgy] enum PcTypedTreeRole:
  case ExpressionExact
  case ExpressionWidened
  case Declared
  case Inferred
  case Parameter
  case Function
  case Pattern

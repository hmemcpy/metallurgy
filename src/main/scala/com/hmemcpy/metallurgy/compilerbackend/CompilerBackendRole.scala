package com.hmemcpy.metallurgy.compilerbackend

private[metallurgy] enum CompilerBackendRole:
  case DeclaredType
  case ExpressionExact
  case ExpressionWidened
  case Definition
  case Binding
  case Function
  case FunctionResult
  case Parameter
  case Pattern
  case PatternExpected

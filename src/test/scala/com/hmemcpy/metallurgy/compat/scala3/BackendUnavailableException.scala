package com.hmemcpy.metallurgy.compat.scala3

/** Raised when the presentation-compiler backend could not publish a snapshot for a case. Distinct from
  * `AssertionError` so an activation failure is greppable and never confused with a wrong type.
  */
final class BackendUnavailableException(message: String) extends RuntimeException(message)

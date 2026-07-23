package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `ApplyConformanceTest` (default `supportedIn` = all versions). The
  * `testSCL13654` case (a bare top-level expression after a class def) is omitted — Metallurgy flags bare top-level
  * statements where the bundled plugin tolerates them (a documented divergence).
  */
final class ApplyConformanceCompatTest extends Scala3CompatTestCase:

  def testCaseClassUniversalApplyWithLambda(): Unit = checkTextHasNoErrors(s"""
       |object test {
       |  final case class Kleisli[F[_], A, B](run: A => F[B])
       |  val f = Kleisli { (x: Int) => Some(x + 1) }
       |}
    """.stripMargin)

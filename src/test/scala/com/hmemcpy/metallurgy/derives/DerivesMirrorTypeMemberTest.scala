package com.hmemcpy.metallurgy.derives

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

/** #43 golden: the bundled plugin resolves `summon[Mirror.Of[T]]` but cannot resolve the Mirror's type members
  * (`MirroredElemLabels`, `MirroredElemTypes`, ...); `pc` runs the real typer and resolves them.
  */
final class DerivesMirrorTypeMemberTest extends MetallurgyFixtureTestCase:
  override protected def featureDir: String  = "derives"
  override protected def fixtureName: String = "mirror_type_member"

  // SCL-23916, SCL-10491 — Type Class Derivation; Mirror type-member consumption.
  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  def testMetallurgyOff(): Unit = assertMetallurgyOff()

package com.hmemcpy.metallurgy.psiproducer

import org.junit.Test

final class TempClosedInventoryDumpTest:

  @Test
  def dumpRoleOwnerships(): Unit =
    val actual = Scala3PsiProductionCatalog.Reviewed.productions
      .flatMap(production => production.grammarRoleIds.map(_ -> production.id))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
    actual.toVector
      .sortBy(_._1.value)
      .foreach: (role, ids) =>
        println(s"[inv] ${role.value} => ${ids.toVector.sorted.mkString(",")}")

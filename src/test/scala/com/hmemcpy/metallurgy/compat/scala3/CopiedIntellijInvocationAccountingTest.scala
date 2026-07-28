package com.hmemcpy.metallurgy.compat.scala3

import com.google.gson.JsonParser
import junit.framework.TestCase
import org.junit.Assert.{assertEquals, assertTrue}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters.*

final class CopiedIntellijInvocationAccountingTest extends TestCase:

  def testEveryGeneratedInvocationAndAdapterContractIsManifested(): Unit =
    val manifest = parse("upstream-tests/intellij-scala.json")
    val suite    = manifest.getAsJsonArray("suites").get(0).getAsJsonObject
    val owner    = suite.getAsJsonObject("generated").get("owner").getAsString
    val expected = suite
      .getAsJsonArray("methods")
      .iterator()
      .asScala
      .map(_.getAsJsonObject.get("localName").getAsString)
      .toSet
    val actual   = Class
      .forName(owner)
      .getMethods
      .iterator
      .filter(method =>
        method.getName.startsWith("test") &&
          method.getParameterCount == 0 &&
          method.getReturnType == java.lang.Void.TYPE
      )
      .map(_.getName)
      .toSet
    assertEquals(expected, actual)

    val adapters  = parse("upstream-tests/adapters.json")
    val contracts = adapters
      .getAsJsonArray("adapters")
      .iterator()
      .asScala
      .flatMap(_.getAsJsonObject.getAsJsonArray("helpers").iterator().asScala)
      .map(_.getAsJsonObject.get("localContractTest").getAsString)
      .toSet
    assertTrue("every adapter helper requires a contract test", contracts.nonEmpty)
    contracts.foreach(contract => Class.forName(contract))

  private def parse(fileName: String) =
    val reader = Files.newBufferedReader(Path.of(fileName), StandardCharsets.UTF_8)
    try JsonParser.parseReader(reader).getAsJsonObject
    finally reader.close()

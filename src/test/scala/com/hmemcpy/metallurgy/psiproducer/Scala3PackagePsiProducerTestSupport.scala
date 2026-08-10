package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture
import com.intellij.util.io.AbstractStringEnumerator
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.junit.Assert.{assertEquals, assertSame, assertTrue}

private[psiproducer] abstract class Scala3PackagePsiProducerTestSupport extends Scala3CompatTestCase:
  protected final def codeInsightFixture: JavaCodeInsightTestFixture = myFixture

  protected final class TestStringEnumerator extends AbstractStringEnumerator:
    private val values = collection.mutable.ArrayBuffer.empty[String]

    override def enumerate(value: String): Int =
      val index = values.indexOf(value)
      if index >= 0 then index + 1
      else
        values += value
        values.size

    override def valueOf(id: Int): String = values(id - 1)
    override def isDirty: Boolean         = false
    override def force(): Unit            = ()
    override def markCorrupted(): Unit    = ()
    override def close(): Unit            = ()
  protected final def assertStablePath(reference: ScStableCodeReference, segments: Vector[String]): Unit =
    var current = Option(reference)
    segments.indices.reverse.foreach: index =>
      val value        = current.getOrElse(throw new AssertionError(s"missing stable path segment ${segments(index)}"))
      val expectedText = segments.take(index + 1).mkString(".")
      assertEquals(expectedText, value.getText)
      assertEquals(segments(index), value.refName)
      assertEquals(segments(index), value.nameId.getText)
      assertEquals(ScalaElementType.REFERENCE, value.getNode.getElementType)
      assertEquals(value.getTextRange.getStartOffset + expectedText.length, value.getTextRange.getEndOffset)
      assertEquals(
        value.getTextRange.getEndOffset - segments(index).length,
        value.nameId.getTextRange.getStartOffset
      )
      assertSame(getProject, value.getProject)
      assertSame(value, value.getNode.getPsi)
      assertSame(value, value.getNavigationElement)
      val qualifier    = value.qualifier
      assertEquals(
        qualifier.toVector,
        value.getChildren.toVector.collect { case child: ScStableCodeReference => child }
      )
      qualifier.foreach(child => assertSame(value, child.getParent))
      current = qualifier
    assertTrue(current.isEmpty)

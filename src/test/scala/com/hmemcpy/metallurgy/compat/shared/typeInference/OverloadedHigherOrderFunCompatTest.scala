package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `OverloadedHigherOrderFunTest` (Scala 2.13+, shared). Tests overloaded
  * higher-order function resolution, SAM types, and prototype collapsing.
  */
final class OverloadedHigherOrderFunCompatTest extends Scala3CompatTestCase:

  def testOverloadedProto(): Unit = checkTextHasNoErrors(
    """
      |object Util:
      |  def mono(x: Int) = x
      |  def poly[T](x: T): T = x
      |
      |trait FunSam[-T, +R]:
      |  def apply(x: T): R
      |
      |trait TFun:
      |  def map[T](f: T => Int): Unit = ()
      |object Fun extends TFun:
      |  import Util.*
      |  def map[T: scala.reflect.ClassTag](f: T => Int): Unit = ()
      |  map(mono)
      |  map(mono _)
      |  map(x => mono(x))
      |""".stripMargin
  )

  def testOverloadedProtoCollapse(): Unit = checkTextHasNoErrors(
    """
      |class Test:
      |  def prepended[B >: Char](elem: B): String = ???
      |  def prepended(c: Char): String = ???
      |  def +:[B >: Char](elem: B): String = prepended(elem)
      |""".stripMargin
  )

  def testEA232097(): Unit = checkTextHasNoErrors(
    """
      |import java.io.File
      |import java.io.FilenameFilter
      |case class ExactMatchFilter(fileName: String) extends FilenameFilter:
      |  override def accept(dir: File, name: String): Boolean = name == fileName
      |
      |def postProcessDownload(extractedDownloadable: File): Unit =
      |  val files = extractedDownloadable.listFiles(ExactMatchFilter(if true then "play.bat" else "play"))
      |  ???
      |""".stripMargin
  )

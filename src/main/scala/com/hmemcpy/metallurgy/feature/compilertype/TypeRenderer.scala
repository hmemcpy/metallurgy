package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.pc.{PcSession, PcSnapshot}
import com.intellij.openapi.diagnostic.Logger

import scala.util.Try

object TypeRenderer {

  private val Log = Logger.getInstance("com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer")

  def render(session: PcSession, snapshot: PcSnapshot, offset: Int): Option[String] = {
    val result: Try[Option[String]] = Try {
      val cl = session.classloader
      val uri = snapshot.fileUri
      val text = snapshot.sourceText

      val paramsClass = Class.forName("scala.meta.pc.OffsetParams", true, cl)
      val pcClass = Class.forName("scala.meta.internal.pc.ScalaPresentationCompiler", true, cl)
      val pc = pcClass.getDeclaredConstructor().newInstance()

      val params = paramsClass.getDeclaredConstructor(
        classOf[String],
        classOf[String],
        classOf[Int]
      ).newInstance(uri, text, Integer.valueOf(offset))

      val typeAtMethod = pcClass.getMethod("typeAt", paramsClass)
      val raw = typeAtMethod.invoke(pc, params)

      raw match {
        case opt: java.util.Optional[_] =>
          if (opt.isPresent) Some(opt.get.toString) else None
        case null => None
        case other => Some(other.toString)
      }
    }

    result.recover {
      case e: Exception =>
        Log.warn(s"typeAt via reflection failed: ${e.getMessage}")
        None
    }.toOption.flatten
  }
}

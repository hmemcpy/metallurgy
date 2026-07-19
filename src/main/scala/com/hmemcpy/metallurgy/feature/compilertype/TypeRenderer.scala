package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.pc.{PcSession, PcSnapshot}
import com.intellij.openapi.diagnostic.Logger

import java.io.File
import java.net.URI
import java.util
import scala.util.Try

object TypeRenderer:

  private val Log = Logger.getInstance("com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer")

  def render(session: PcSession, snapshot: PcSnapshot, offset: Int): Option[String] =
    Try(renderInlineType(session, snapshot, offset)).recover { case e: Exception =>
      Log.warn(s"Scala 3 typed-tree lookup failed: ${e.getMessage}", e)
      None
    }.get

  private def renderInlineType(session: PcSession, snapshot: PcSnapshot, offset: Int): Option[String] =
    val cl             = session.classloader
    val uri            = URI.create(snapshot.fileUri)
    val settings       = compilerSettings(session)
    val driverClass    = Class.forName("dotty.tools.dotc.interactive.InteractiveDriver", true, cl)
    val scalaListClass = Class.forName("scala.collection.immutable.List", true, cl)
    val driver         = driverClass.getConstructor(scalaListClass).newInstance(settings)

    driverClass.getMethod("run", classOf[URI], classOf[String]).invoke(driver, uri, snapshot.sourceText)
    val context    = driverClass.getMethod("currentCtx").invoke(driver)
    val sourceFile = mapValue(driverClass.getMethod("openedFiles").invoke(driver), uri)
    val trees      = mapValue(driverClass.getMethod("openedTrees").invoke(driver), uri)
    val span       = spansModule(cl).getClass.getMethod("Span", classOf[Int]).invoke(spansModule(cl), Int.box(offset))
    val position   = sourceFile.getClass.getMethod("atSpan", java.lang.Long.TYPE).invoke(sourceFile, span)
    val path       = pathTo(cl, trees, position, context)

    firstInlinedType(path, context)

  private def compilerSettings(session: PcSession): AnyRef =
    val settings    = util.List.of(
      "-classpath",
      session.compilerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator),
      "-Ywith-best-effort-tasty"
    )
    val converters  = Class.forName("scala.jdk.javaapi.CollectionConverters", true, session.classloader)
    val scalaBuffer = converters.getMethod("asScala", classOf[util.List[?]]).invoke(null, settings)
    scalaBuffer.getClass.getMethod("toList").invoke(scalaBuffer)

  private def mapValue(scalaMap: AnyRef, key: AnyRef): AnyRef =
    scalaMap.getClass.getMethod("apply", classOf[Object]).invoke(scalaMap, key)

  private def spansModule(classloader: ClassLoader): AnyRef =
    Class.forName("dotty.tools.dotc.util.Spans$", true, classloader).getField("MODULE$").get(null)

  private def pathTo(classloader: ClassLoader, trees: AnyRef, position: AnyRef, context: AnyRef): AnyRef =
    val interactive = Class
      .forName("dotty.tools.dotc.interactive.Interactive$", true, classloader)
      .getField("MODULE$")
      .get(null)
    val arguments   = Array(trees, position, context)
    interactive.getClass.getMethods
      .find: method =>
        method.getName == "pathTo" &&
          method.getParameterTypes.zip(arguments).forall((parameter, argument) => parameter.isInstance(argument))
      .getOrElse(throw new NoSuchMethodException("Interactive.pathTo"))
      .invoke(interactive, arguments*)

  private def firstInlinedType(path: AnyRef, context: AnyRef): Option[String] =
    val iterator = path.getClass.getMethod("iterator").invoke(path)
    val hasNext  = iterator.getClass.getMethod("hasNext")
    val next     = iterator.getClass.getMethod("next")
    Iterator
      .continually(iterator)
      .takeWhile(it => hasNext.invoke(it).asInstanceOf[Boolean])
      .map(it => next.invoke(it).asInstanceOf[AnyRef])
      .find(_.getClass.getSimpleName == "Inlined")
      .map { tree =>
        val tpe = tree.getClass.getMethod("tpe").invoke(tree)
        tpe.getClass.getMethods
          .find(method => method.getName == "show" && method.getParameterCount == 1)
          .getOrElse(throw new NoSuchMethodException("Type.show"))
          .invoke(tpe, context)
          .toString
          .replaceAll("\\u001B\\[[;\\d]*m", "")
      }

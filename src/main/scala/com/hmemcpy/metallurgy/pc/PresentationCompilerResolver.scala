package com.hmemcpy.metallurgy.pc

import java.nio.file.Path
import scala.util.control.NonFatal

private[pc] trait PresentationCompilerResolver:
  def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]]

private[pc] object PresentationCompilerResolver:
  val bundled: PresentationCompilerResolver = BundledDependencyManagerResolver

/** Uses the bundled Scala plugin's Ivy resolver, preserving its proxy, cancellation, repository, and cache behavior. */
private object BundledDependencyManagerResolver extends PresentationCompilerResolver:

  private lazy val bundledClassLoader =
    Class.forName("org.jetbrains.plugins.scala.DependencyManager$").getClassLoader

  override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
    try Right(resolvedFiles(transitiveDependency(scalaVersion)))
    catch case NonFatal(error) => Left(ArtifactResolutionError.DependencyResolution(scalaVersion, error))

  private def transitiveDependency(scalaVersion: String): AnyRef =
    val descriptionClass = Class.forName(
      "org.jetbrains.plugins.scala.DependencyManagerBase$DependencyDescription",
      true,
      bundledClassLoader
    )
    val types            = module("org.jetbrains.plugins.scala.DependencyManagerBase$Types$")
    val jarType          = types.getClass.getMethod("JAR").invoke(types)
    val nil              = module("scala.collection.immutable.Nil$")
    val constructor      = descriptionClass.getConstructors
      .find(_.getParameterCount == 7)
      .getOrElse:
        throw new NoSuchMethodException("DependencyDescription constructor")
    val dependency       = constructor.newInstance(
      "org.scala-lang",
      "scala3-presentation-compiler_3",
      scalaVersion,
      "compile->default(compile)",
      jarType,
      Boolean.box(false),
      nil
    )
    descriptionClass.getMethod("transitive").invoke(dependency).asInstanceOf[AnyRef]

  private def resolvedFiles(dependency: AnyRef): Seq[Path] =
    val dependencies = cons(dependency)
    val manager      = module("org.jetbrains.plugins.scala.DependencyManager$")
    val scalaSeq     = Class.forName("scala.collection.immutable.Seq", true, bundledClassLoader)
    val resolved     = manager.getClass.getMethod("resolve", scalaSeq).invoke(manager, dependencies)
    val iterator     = resolved.getClass.getMethod("iterator").invoke(resolved)
    val hasNext      = iterator.getClass.getMethod("hasNext")
    val next         = iterator.getClass.getMethod("next")

    Iterator
      .continually(iterator)
      .takeWhile(value => hasNext.invoke(value).asInstanceOf[Boolean])
      .map(value => next.invoke(value))
      .map(result => result.getClass.getMethod("file").invoke(result).asInstanceOf[Path])
      .toSeq

  private def cons(value: AnyRef): AnyRef =
    val nil         = module("scala.collection.immutable.Nil$")
    val listClass   = Class.forName("scala.collection.immutable.List", true, bundledClassLoader)
    val constructor = Class
      .forName("scala.collection.immutable.$colon$colon", true, bundledClassLoader)
      .getConstructor(classOf[Object], listClass)
    constructor.newInstance(value, nil).asInstanceOf[AnyRef]

  private def module(className: String): AnyRef =
    Class.forName(className, true, bundledClassLoader).getField("MODULE$").get(null).asInstanceOf[AnyRef]

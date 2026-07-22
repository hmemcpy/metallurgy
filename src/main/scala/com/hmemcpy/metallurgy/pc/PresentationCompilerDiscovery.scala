package com.hmemcpy.metallurgy.pc

import org.jetbrains.org.objectweb.asm.{ClassReader, ClassVisitor, Opcodes}
import scala.meta.pc.PresentationCompiler

import java.io.File
import java.lang.reflect.Modifier
import java.util.ServiceConfigurationError
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

/** Finds a presentation-compiler implementation by capability rather than compiler version or implementation name. */
private[pc] object PresentationCompilerDiscovery:

  private final case class ClassShape(internalName: String, superName: Option[String], access: Int):
    def isPublicConcrete: Boolean =
      (access & Opcodes.ACC_PUBLIC) != 0 &&
        (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0

  private val PresentationCompilerInternalName = classOf[PresentationCompiler].getName.replace('.', '/')

  def load(classloader: ClassLoader, compilerArtifacts: Seq[File]): Either[String, PresentationCompiler] =
    serviceProvider(classloader)
      .orElse(structuralProvider(classloader, compilerArtifacts))
      .toRight("No compatible Scala presentation compiler provider found")

  private def serviceProvider(classloader: ClassLoader): Option[PresentationCompiler] =
    try
      val providers = java.util.ServiceLoader.load(classOf[PresentationCompiler], classloader).iterator()
      Option.when(providers.hasNext)(providers.next())
    catch
      case _: ServiceConfigurationError => None
      case NonFatal(_)                  => None

  private def structuralProvider(
      classloader: ClassLoader,
      compilerArtifacts: Seq[File]
  ): Option[PresentationCompiler] =
    val shapes = compilerArtifacts.iterator
      .filter(file => file.isFile && file.getName.endsWith(".jar"))
      .flatMap(readShapes)
      .map(shape => shape.internalName -> shape)
      .toMap

    shapes.valuesIterator
      .filter(shape => shape.isPublicConcrete && derivesFromPresentationCompiler(shape.internalName, shapes))
      .map(_.internalName.replace('/', '.'))
      .toVector
      .sorted
      .iterator
      .flatMap(className => instantiate(classloader, className))
      .nextOption()

  private def instantiate(classloader: ClassLoader, className: String): Option[PresentationCompiler] =
    try
      val implementation = Class.forName(className, false, classloader)
      Option
        .when(
          classOf[PresentationCompiler].isAssignableFrom(implementation) &&
            Modifier.isPublic(implementation.getModifiers) &&
            !Modifier.isAbstract(implementation.getModifiers)
        ):
          implementation.getConstructor().newInstance().asInstanceOf[PresentationCompiler]
    catch case NonFatal(_) => None

  private def derivesFromPresentationCompiler(
      internalName: String,
      shapes: Map[String, ClassShape],
      visited: Set[String] = Set.empty
  ): Boolean =
    internalName != PresentationCompilerInternalName &&
      !visited(internalName) && shapes
        .get(internalName)
        .flatMap(_.superName)
        .exists(parent =>
          parent == PresentationCompilerInternalName ||
            derivesFromPresentationCompiler(parent, shapes, visited + internalName)
        )

  private def readShapes(jarFile: File): Iterator[ClassShape] =
    try readJarShapes(jarFile).iterator
    catch case NonFatal(_) => Iterator.empty

  private def readJarShapes(jarFile: File): Vector[ClassShape] =
    Using.resource(new JarFile(jarFile)): jar =>
      jar
        .entries()
        .asScala
        .filter(entry => !entry.isDirectory && entry.getName.endsWith(".class"))
        .flatMap: entry =>
          try Some(readShape(Using.resource(jar.getInputStream(entry))(_.readAllBytes())))
          catch case NonFatal(_) => None
        .toVector

  private def readShape(bytes: Array[Byte]): ClassShape =
    var result: Option[ClassShape] = None
    new ClassReader(bytes).accept(
      new ClassVisitor(Opcodes.ASM9):
        override def visit(
            version: Int,
            access: Int,
            name: String,
            signature: String,
            superName: String,
            interfaces: Array[String]
        ): Unit =
          result = Some(ClassShape(name, Option(superName), access))
      ,
      ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
    )
    result.getOrElse(throw new IllegalArgumentException("class file has no header"))

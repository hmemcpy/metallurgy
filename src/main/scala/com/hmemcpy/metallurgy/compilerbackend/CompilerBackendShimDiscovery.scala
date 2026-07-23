package com.hmemcpy.metallurgy.compilerbackend

import org.jetbrains.org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

private[compilerbackend] final case class CompilerBackendShimMethod(
    name: String,
    descriptor: String,
    role: CompilerBackendRole,
    elementLocal: Int,
    resultInternalName: String
)

private[compilerbackend] final case class CompilerBackendShimTarget(
    className: String,
    methods: Vector[CompilerBackendShimMethod]
):
  val internalName: String = className.replace('.', '/')

private[compilerbackend] final case class CompilerTypeShimTarget(
    className: String,
    methodName: String,
    descriptor: String,
    elementLocal: Int
):
  val internalName: String = className.replace('.', '/')

private[compilerbackend] final case class CompilerBackendResolveShimTarget(
    className: String,
    methodName: String,
    descriptor: String,
    elementLocal: Int
):
  val internalName: String = className.replace('.', '/')

private[compilerbackend] final case class CompilerBackendRawTypeShimTarget(
    className: String,
    methodName: String,
    descriptor: String,
    elementLocal: Int
):
  val internalName: String = className.replace('.', '/')

private[compilerbackend] final case class CompilerBackendPatternImplementation(
    className: String,
    hookClassName: Option[String]
)

private[compilerbackend] final case class CompilerBackendShimDiscoveryResult(
    semanticTargets: Vector[CompilerBackendShimTarget],
    compilerTypeTarget: Option[CompilerTypeShimTarget],
    resolveTargets: Vector[CompilerBackendResolveShimTarget],
    rawTypeTargets: Vector[CompilerBackendRawTypeShimTarget],
    patternImplementations: Vector[CompilerBackendPatternImplementation],
    unavailableRoots: Vector[String],
    unavailableAdapters: Vector[String]
):
  def canInstall: Boolean = semanticTargets.nonEmpty && unavailableRoots.isEmpty

/** Discovers semantic type roots from the installed Scala plugin's bytecode. Compatibility is structural: a stable,
  * EAP, or nightly build is accepted when it exposes the required PSI ancestry and JVM method descriptors.
  */
private[compilerbackend] object CompilerBackendShimDiscovery:

  private final case class MethodShape(name: String, descriptor: String, access: Int):
    def hasBody: Boolean  = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
    def isStatic: Boolean = (access & Opcodes.ACC_STATIC) != 0

  private final case class ClassShape(
      internalName: String,
      superName: Option[String],
      interfaces: Vector[String],
      access: Int,
      methods: Vector[MethodShape]
  ):
    def isConcrete: Boolean     = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0
    def parents: Vector[String] = superName.toVector ++ interfaces

  private final case class RootSpec(
      label: String,
      rootInternalName: String,
      methodName: String,
      descriptor: String,
      role: CompilerBackendRole,
      resultInternalName: String
  )

  private final case class ResolveRootSpec(
      label: String,
      rootInternalName: String,
      methodName: String,
      descriptor: String
  )

  private val PsiPrefix        = "org/jetbrains/plugins/scala/lang/psi/"
  private val EitherResult     = "scala/util/Either"
  private val EitherDescriptor = "()Lscala/util/Either;"
  private val PatternRoot      = "org/jetbrains/plugins/scala/lang/psi/api/base/patterns/ScPattern"
  private val ResolveResult    = "org/jetbrains/plugins/scala/lang/resolve/ScalaResolveResult"
  private val ResolveArray     = s"[L$ResolveResult;"

  private val rootSpecs = Vector(
    RootSpec(
      "declared type",
      "org/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement",
      "type",
      EitherDescriptor,
      CompilerBackendRole.DeclaredType,
      EitherResult
    ),
    RootSpec(
      "value or variable",
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable",
      "type",
      EitherDescriptor,
      CompilerBackendRole.Definition,
      EitherResult
    ),
    RootSpec(
      "function result",
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction",
      "returnType",
      EitherDescriptor,
      CompilerBackendRole.FunctionResult,
      EitherResult
    ),
    RootSpec(
      "parameter",
      "org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameter",
      "type",
      EitherDescriptor,
      CompilerBackendRole.Parameter,
      EitherResult
    ),
    RootSpec(
      "pattern",
      PatternRoot,
      "type",
      EitherDescriptor,
      CompilerBackendRole.Pattern,
      EitherResult
    )
  )

  private val resolveRootSpecs = Vector(
    ResolveRootSpec(
      "expression reference resolution",
      "org/jetbrains/plugins/scala/lang/psi/api/expr/ScReferenceExpression",
      "multiResolveScala",
      s"(Z)$ResolveArray"
    ),
    ResolveRootSpec(
      "stable reference resolution",
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScStableCodeReference",
      "doResolve",
      s"(Lorg/jetbrains/plugins/scala/lang/resolve/processor/BaseProcessor;Z)$ResolveArray"
    )
  )

  private val CompilerTypeClass         = "org/jetbrains/plugins/scala/lang/psi/impl/CompilerType$"
  private val CompilerTypeMethod        = "apply"
  private val CompilerTypeDescriptor    = "(Lcom/intellij/psi/PsiElement;)Lscala/Option;"
  private val ExpectedTypeMethod        = "expectedType$extension"
  private val ExpectedTypeDescriptor    =
    "(Lorg/jetbrains/plugins/scala/lang/psi/api/base/patterns/ScPattern;)Lscala/Option;"
  private val RefactoringPrefix         = "org/jetbrains/plugins/scala/lang/refactoring/"
  private val RefactoringTypeMethod     = "typeWithoutExpected"
  private val RefactoringTypeDescriptor =
    "(Lorg/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression;)Lorg/jetbrains/plugins/scala/lang/psi/types/ScType;"

  def discover(anchor: Class[?]): Either[String, CompilerBackendShimDiscoveryResult] =
    findClassRoot(anchor)
      .toRight(s"no code source for ${anchor.getName}")
      .flatMap: location =>
        try Right(discover(readShapes(location)))
        catch case error: Exception => Left(s"cannot inspect installed Scala plugin: ${error.getMessage}")

  private def findClassRoot(anchor: Class[?]): Option[Path] =
    // Primary: protection domain code source (works for most production installs)
    Option(anchor.getProtectionDomain)
      .flatMap(domain => Option(domain.getCodeSource))
      .flatMap(source => Option(source.getLocation))
      .flatMap(location => Try(Path.of(location.toURI)).toOption)
      .orElse:
        // Fallback: resolve via the class resource URL. IntelliJ's PluginClassLoader does not always populate
        // the protection domain's code source, so the JAR or class-directory root is derived from the resource.
        val className = anchor.getName.replace('.', '/') + ".class"
        Option(anchor.getResource("/" + className)).flatMap(rootFromResourceUrl(_, className))

  private def rootFromResourceUrl(url: java.net.URL, className: String): Option[Path] =
    Try:
      url.getProtocol match
        case "jar"  =>
          val file      = url.getFile
          val separator = file.indexOf("!")
          Path.of(URI.create(file.substring(0, separator)))
        case "file" =>
          val path  = Paths.get(url.toURI)
          val depth = className.count(_ == '/') + 1
          var root  = path
          for _ <- 0 until depth do root = root.getParent
          root
        case _      => null.asInstanceOf[Path]
    .toOption.filter(_ != null)

  private[compilerbackend] def discoverClassBytes(
      classes: Iterable[Array[Byte]]
  ): CompilerBackendShimDiscoveryResult =
    discover(classes.iterator.map(readShape).map(shape => shape.internalName -> shape).toMap)

  private def discover(shapes: Map[String, ClassShape]): CompilerBackendShimDiscoveryResult =
    val ordinaryTargets     = rootSpecs.flatMap(spec => targetsFor(spec, shapes))
    val expectedTargets     = shapes.valuesIterator.flatMap: shape =>
      shape.methods
        .filter(method =>
          method.hasBody && method.name == ExpectedTypeMethod && method.descriptor == ExpectedTypeDescriptor
        )
        .map: method =>
          val elementLocal = if method.isStatic then 0 else 1
          CompilerBackendShimTarget(
            shape.internalName.replace('/', '.'),
            Vector(
              CompilerBackendShimMethod(
                method.name,
                method.descriptor,
                CompilerBackendRole.PatternExpected,
                elementLocal,
                "scala/Option"
              )
            )
          )
    val targets             = combineTargets(ordinaryTargets ++ expectedTargets)
    val resolveTargets      = resolveRootSpecs.flatMap(spec => resolveTargetsFor(spec, shapes))
    val rawTypeTargets      = shapes.valuesIterator
      .filter(_.internalName.startsWith(RefactoringPrefix))
      .flatMap: shape =>
        shape.methods.collect:
          case method
              if method.hasBody && method.name == RefactoringTypeMethod &&
                method.descriptor == RefactoringTypeDescriptor =>
            CompilerBackendRawTypeShimTarget(
              shape.internalName.replace('/', '.'),
              method.name,
              method.descriptor,
              if method.isStatic then 0 else 1
            )
      .toVector
    val unavailableAdapters = resolveRootSpecs.collect:
      case spec if !resolveTargets.exists(target => target.methodName == spec.methodName) => spec.label
    val rawTypeUnavailable  = Option.when(rawTypeTargets.isEmpty)("introduce variable exact type")
    val patterns            = shapes.valuesIterator
      .filter(shape => shape.isConcrete && derivesFrom(shape.internalName, PatternRoot, shapes))
      .map: shape =>
        val hookOwner = concreteMethodOwner(shape.internalName, "type", EitherDescriptor, shapes)
        CompilerBackendPatternImplementation(
          shape.internalName.replace('/', '.'),
          hookOwner.map(_.replace('/', '.'))
        )
      .toVector
      .sortBy(_.className)
    val uncovered           = patterns.filter(_.hookClassName.isEmpty)
    val unavailable         = rootSpecs.collect:
      case spec if !targets.exists(_.methods.exists(_.role == spec.role)) => spec.label
    val expectedMissing     = Option.when(!targets.exists(_.methods.exists(_.role == CompilerBackendRole.PatternExpected)))(
      "pattern expected type"
    )
    CompilerBackendShimDiscoveryResult(
      semanticTargets = targets,
      compilerTypeTarget = compilerTypeTarget(shapes),
      resolveTargets = resolveTargets,
      rawTypeTargets = rawTypeTargets,
      patternImplementations = patterns,
      unavailableRoots = unavailable ++ expectedMissing.toVector ++ uncovered.map(pattern =>
        s"pattern implementation ${pattern.className}"
      ),
      unavailableAdapters = unavailableAdapters ++ rawTypeUnavailable
    )

  private def resolveTargetsFor(
      spec: ResolveRootSpec,
      shapes: Map[String, ClassShape]
  ): Vector[CompilerBackendResolveShimTarget] =
    shapes.valuesIterator
      .filter(shape => derivesFrom(shape.internalName, spec.rootInternalName, shapes))
      .flatMap: shape =>
        shape.methods.collect:
          case method
              if method.hasBody && !method.isStatic && method.name == spec.methodName &&
                method.descriptor == spec.descriptor =>
            CompilerBackendResolveShimTarget(
              shape.internalName.replace('/', '.'),
              method.name,
              method.descriptor,
              elementLocal = 0
            )
      .toVector
      .sortBy(_.className)

  private def targetsFor(spec: RootSpec, shapes: Map[String, ClassShape]): Vector[CompilerBackendShimTarget] =
    val candidates =
      if spec.role == CompilerBackendRole.DeclaredType then shapes.get(spec.rootInternalName).iterator
      else shapes.valuesIterator
    candidates
      .flatMap: shape =>
        Option
          .when(derivesFrom(shape.internalName, spec.rootInternalName, shapes)):
            val methods = shape.methods.collect:
              case method if method.hasBody && method.name == spec.methodName && method.descriptor == spec.descriptor =>
                CompilerBackendShimMethod(
                  method.name,
                  method.descriptor,
                  spec.role,
                  if method.isStatic then 0 else 0,
                  spec.resultInternalName
                )
            Option.when(methods.nonEmpty)(CompilerBackendShimTarget(shape.internalName.replace('/', '.'), methods))
          .flatten
      .toVector

  private def compilerTypeTarget(shapes: Map[String, ClassShape]): Option[CompilerTypeShimTarget] =
    shapes
      .get(CompilerTypeClass)
      .flatMap: shape =>
        shape.methods
          .find(method =>
            method.hasBody && method.name == CompilerTypeMethod && method.descriptor == CompilerTypeDescriptor
          )
          .map: method =>
            CompilerTypeShimTarget(
              shape.internalName.replace('/', '.'),
              method.name,
              method.descriptor,
              if method.isStatic then 0 else 1
            )

  private def combineTargets(targets: Iterable[CompilerBackendShimTarget]): Vector[CompilerBackendShimTarget] =
    targets
      .groupMapReduce(_.className)(_.methods)((left, right) => left ++ right)
      .iterator
      .map: (className, methods) =>
        CompilerBackendShimTarget(className, methods.distinct)
      .toVector
      .sortBy(_.className)

  private def concreteMethodOwner(
      internalName: String,
      methodName: String,
      descriptor: String,
      shapes: Map[String, ClassShape],
      visited: Set[String] = Set.empty
  ): Option[String] =
    if visited(internalName) then None
    else
      shapes
        .get(internalName)
        .flatMap: shape =>
          if shape.methods
              .exists(method => method.hasBody && method.name == methodName && method.descriptor == descriptor)
          then Some(internalName)
          else
            shape.parents.iterator
              .flatMap(parent => concreteMethodOwner(parent, methodName, descriptor, shapes, visited + internalName))
              .nextOption()

  private def derivesFrom(
      internalName: String,
      rootInternalName: String,
      shapes: Map[String, ClassShape],
      visited: Set[String] = Set.empty
  ): Boolean =
    internalName == rootInternalName ||
      !visited(internalName) && shapes
        .get(internalName)
        .exists: shape =>
          shape.parents.exists(parent => derivesFrom(parent, rootInternalName, shapes, visited + internalName))

  private def readShapes(root: Path): Map[String, ClassShape] =
    val shapes =
      if Files.isDirectory(root) then readDirectoryShapes(root)
      else readJarShapes(root)
    shapes.iterator.map(shape => shape.internalName -> shape).toMap

  private def readJarShapes(path: Path): Vector[ClassShape] =
    Using.resource(new JarFile(path.toFile)): jar =>
      jar
        .entries()
        .asScala
        .filter(entry => !entry.isDirectory && isPsiClass(entry.getName))
        .map: entry =>
          Using.resource(jar.getInputStream(entry))(stream => readShape(stream.readAllBytes()))
        .toVector

  private def readDirectoryShapes(root: Path): Vector[ClassShape] =
    Using.resource(Files.walk(root)): paths =>
      paths
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && isPsiClass(root.relativize(path).toString.replace('\\', '/')))
        .map(path => readShape(Files.readAllBytes(path)))
        .toVector

  private def isPsiClass(path: String): Boolean =
    path.endsWith(".class") &&
      (path.startsWith(PsiPrefix) || path.startsWith(RefactoringPrefix))

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
          result = Some(ClassShape(name, Option(superName), interfaces.toVector, access, Vector.empty))

        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          result = result.map(shape => shape.copy(methods = shape.methods :+ MethodShape(name, descriptor, access)))
          null
      ,
      ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
    )
    result.getOrElse(throw new IllegalArgumentException("class file has no header"))

package com.hmemcpy.metallurgy.compilerbackend

import net.bytebuddy.agent.ByteBuddyAgent
import org.jetbrains.org.objectweb.asm.commons.AdviceAdapter
import org.jetbrains.org.objectweb.asm.{ClassReader, ClassVisitor, ClassWriter, Label, MethodVisitor, Opcodes}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement

import java.lang.instrument.{ClassFileTransformer, Instrumentation}
import java.lang.invoke.MethodHandles
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.function.{BiFunction, Function}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[compilerbackend] object BundledCompilerBackendShim:

  private enum Installation:
    case NotStarted
    case Installing
    case Finished(status: CompilerBackendShimStatus)

  private val BridgeClassName    =
    "org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge"
  private val BridgeInternalName = BridgeClassName.replace('.', '/')

  private val installation = new AtomicReference[Installation](Installation.NotStarted)
  private val installLock  = new Object

  def install(): CompilerBackendShimStatus =
    installLock.synchronized:
      installation.get() match
        case Installation.Finished(status) => status
        case Installation.Installing       => CompilerBackendShimStatus.Disabled("recursive installation")
        case Installation.NotStarted       =>
          installation.set(Installation.Installing)
          val status = attemptInstall()
          installation.set(Installation.Finished(status))
          status

  private def attemptInstall(): CompilerBackendShimStatus =
    CompilerBackendShimDiscovery.discover(classOf[ScTypeElement]) match
      case Left(reason)                              => CompilerBackendShimStatus.Disabled(reason)
      case Right(discovery) if !discovery.canInstall =>
        CompilerBackendShimStatus.Disabled(
          s"no compatible Scala PSI type roots were found: ${discovery.unavailableRoots.mkString(", ")}"
        )
      case Right(discovery)                          =>
        try
          val instrumentation = ByteBuddyAgent.install()
          if !instrumentation.isRetransformClassesSupported then
            CompilerBackendShimStatus.Disabled("JVM does not support class retransformation")
          else installDiscovered(instrumentation, classOf[ScTypeElement], discovery)
        catch case NonFatal(error) => CompilerBackendShimStatus.Disabled(error.toString)

  private def installDiscovered(
      instrumentation: Instrumentation,
      anchorClass: Class[?],
      discovery: CompilerBackendShimDiscoveryResult
  ): CompilerBackendShimStatus =
    val targetLoader         = anchorClass.getClassLoader
    val semanticClasses      = discovery.semanticTargets.flatMap: target =>
      loadTarget(targetLoader, target.className).map(target -> _)
    val compilerTypeClass    = discovery.compilerTypeTarget.flatMap: target =>
      loadTarget(targetLoader, target.className).map(target -> _)
    val resolveClasses       = discovery.resolveTargets.flatMap: target =>
      loadTarget(targetLoader, target.className).map(target -> _)
    val semanticLoadFailures =
      discovery.semanticTargets.filterNot(target => semanticClasses.exists(_._1 == target)).map(_.className) ++
        discovery.compilerTypeTarget.toVector
          .filterNot(target => compilerTypeClass.exists(_._1 == target))
          .map(_.className)
    val resolveLoadFailures  = discovery.resolveTargets
      .filterNot(target => resolveClasses.exists(_._1 == target))
      .map(_.className)
    val semanticMethods      = ConcurrentHashMap.newKeySet[String]()
    val resolveMethods       = ConcurrentHashMap.newKeySet[String]()
    val compilerTypeHooked   = new AtomicBoolean(false)
    val transformer          = transformerFor(
      targetLoader,
      discovery.semanticTargets,
      discovery.compilerTypeTarget,
      discovery.resolveTargets,
      semanticMethods,
      resolveMethods,
      compilerTypeHooked
    )
    val bridge               = defineBridge(anchorClass)
    var transformerAdded     = false
    var backendInstalled     = false
    try
      installBackend(bridge)
      backendInstalled = true
      instrumentation.addTransformer(transformer, true)
      transformerAdded = true
      val semanticTransformFailures = (semanticClasses.map(_._2) ++ compilerTypeClass.map(_._2)).distinct.flatMap:
        targetClass => retransform(instrumentation, targetClass).left.toOption
      val resolveTransformFailures  = resolveClasses
        .map(_._2)
        .distinct
        .flatMap: targetClass =>
          retransform(instrumentation, targetClass).left.toOption
      val expectedMethods           = semanticClasses.flatMap: (target, _) =>
        target.methods.map(methodKey(target, _))
      val missingMethods            = expectedMethods.filterNot(semanticMethods.contains)
      val expectedResolve           = resolveClasses.map((target, _) => resolveMethodKey(target))
      val missingResolve            = expectedResolve.filterNot(resolveMethods.contains)
      val unavailableRoots          = (
        discovery.unavailableRoots ++
          semanticLoadFailures.map(name => s"unloadable $name") ++
          semanticTransformFailures ++
          missingMethods.map(key => s"untransformed $key") ++
          Option.when(discovery.compilerTypeTarget.nonEmpty && !compilerTypeHooked.get())("CompilerType.apply")
      ).distinct
      val unavailableAdapters       = (
        discovery.unavailableAdapters ++
          resolveLoadFailures.map(name => s"unloadable $name") ++
          resolveTransformFailures ++
          missingResolve.map(key => s"untransformed $key")
      ).distinct
      if semanticMethods.isEmpty || unavailableRoots.nonEmpty then
        uninstallBackend(bridge)
        val reason =
          if unavailableRoots.nonEmpty then s"incompatible Scala PSI type roots: ${unavailableRoots.mkString(", ")}"
          else "no compatible Scala PSI type roots were found"
        CompilerBackendShimStatus.Disabled(reason)
      else
        bridge.getMethod("enable").invoke(null)
        CompilerBackendShimStatus.Enabled(
          semanticMethods.size() + resolveMethods.size() + Option.when(compilerTypeHooked.get())(1).sum,
          unavailableAdapters
        )
    catch
      case NonFatal(error) =>
        if backendInstalled then uninstallBackend(bridge)
        CompilerBackendShimStatus.Disabled(error.toString)
    finally
      if transformerAdded then
        val _ = instrumentation.removeTransformer(transformer)

  private def loadTarget(loader: ClassLoader, className: String): Option[Class[?]] =
    try Some(Class.forName(className, false, loader))
    catch case NonFatal(_) => None

  private def retransform(instrumentation: Instrumentation, targetClass: Class[?]): Either[String, Unit] =
    if !instrumentation.isModifiableClass(targetClass) then Left(s"unmodifiable ${targetClass.getName}")
    else
      try
        instrumentation.retransformClasses(targetClass)
        Right(())
      catch case NonFatal(error) => Left(s"${targetClass.getName}: ${error.getClass.getSimpleName}")

  private def transformerFor(
      targetLoader: ClassLoader,
      semanticTargets: Vector[CompilerBackendShimTarget],
      compilerTypeTarget: Option[CompilerTypeShimTarget],
      resolveTargets: Vector[CompilerBackendResolveShimTarget],
      semanticMethods: java.util.Set[String],
      resolveMethods: java.util.Set[String],
      compilerTypeHooked: AtomicBoolean
  ): ClassFileTransformer =
    val semanticByName = semanticTargets.iterator.map(target => target.internalName -> target).toMap
    val resolveByName  = resolveTargets.iterator.map(target => target.internalName -> target).toMap
    new ClassFileTransformer:
      override def transform(
          loader: ClassLoader,
          className: String,
          classBeingRedefined: Class[?],
          protectionDomain: ProtectionDomain,
          classfileBuffer: Array[Byte]
      ): Array[Byte] =
        if loader != targetLoader then null
        else
          semanticByName.get(className) match
            case Some(target) => transformSemanticTarget(classfileBuffer, target, semanticMethods)
            case None         =>
              resolveByName.get(className) match
                case Some(target) => transformResolveTarget(classfileBuffer, target, resolveMethods)
                case None         =>
                  compilerTypeTarget.filter(_.internalName == className) match
                    case Some(target) =>
                      val output = transformCompilerType(classfileBuffer, target)
                      compilerTypeHooked.set(true)
                      output
                    case None         => null

  private def transformSemanticTarget(
      original: Array[Byte],
      target: CompilerBackendShimTarget,
      transformedMethods: java.util.Set[String]
  ): Array[Byte] =
    val reader = new ClassReader(original)
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    var found  = Set.empty[String]
    reader.accept(
      new ClassVisitor(Opcodes.ASM9, writer):
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          val originalMethod = super.visitMethod(access, name, descriptor, signature, exceptions)
          target.methods.find(method => method.name == name && method.descriptor == descriptor) match
            case None         => originalMethod
            case Some(method) =>
              found += methodKey(target, method)
              new AdviceAdapter(Opcodes.ASM9, originalMethod, access, name, descriptor):
                override def onMethodEnter(): Unit =
                  val fallback = new Label()
                  visitVarInsn(Opcodes.ALOAD, method.elementLocal)
                  if method.role == CompilerBackendRole.DeclaredType then
                    visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      BridgeInternalName,
                      "declaredType",
                      "(Ljava/lang/Object;)Ljava/lang/Object;",
                      false
                    )
                  else
                    visitLdcInsn(Integer.valueOf(method.role.ordinal))
                    visitMethodInsn(
                      Opcodes.INVOKESTATIC,
                      BridgeInternalName,
                      "semanticType",
                      "(Ljava/lang/Object;I)Ljava/lang/Object;",
                      false
                    )
                  visitInsn(Opcodes.DUP)
                  visitJumpInsn(Opcodes.IFNULL, fallback)
                  visitTypeInsn(Opcodes.CHECKCAST, method.resultInternalName)
                  visitInsn(Opcodes.ARETURN)
                  visitLabel(fallback)
                  visitInsn(Opcodes.POP)
      ,
      ClassReader.EXPAND_FRAMES
    )
    val output = writer.toByteArray
    val _      = transformedMethods.addAll(found.asJava)
    output

  private def transformCompilerType(original: Array[Byte], target: CompilerTypeShimTarget): Array[Byte] =
    val reader = new ClassReader(original)
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    reader.accept(
      new ClassVisitor(Opcodes.ASM9, writer):
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          val originalMethod = super.visitMethod(access, name, descriptor, signature, exceptions)
          if name != target.methodName || descriptor != target.descriptor then originalMethod
          else
            new AdviceAdapter(Opcodes.ASM9, originalMethod, access, name, descriptor):
              override def onMethodEnter(): Unit =
                val fallback = new Label()
                visitVarInsn(Opcodes.ALOAD, target.elementLocal)
                visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  BridgeInternalName,
                  "compilerType",
                  "(Ljava/lang/Object;)Ljava/lang/Object;",
                  false
                )
                visitInsn(Opcodes.DUP)
                visitJumpInsn(Opcodes.IFNULL, fallback)
                visitTypeInsn(Opcodes.CHECKCAST, "scala/Option")
                visitInsn(Opcodes.ARETURN)
                visitLabel(fallback)
                visitInsn(Opcodes.POP)
      ,
      ClassReader.EXPAND_FRAMES
    )
    writer.toByteArray

  private def transformResolveTarget(
      original: Array[Byte],
      target: CompilerBackendResolveShimTarget,
      transformedMethods: java.util.Set[String]
  ): Array[Byte] =
    val reader = new ClassReader(original)
    val writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
    var found  = false
    reader.accept(
      new ClassVisitor(Opcodes.ASM9, writer):
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          val originalMethod = super.visitMethod(access, name, descriptor, signature, exceptions)
          if name != target.methodName || descriptor != target.descriptor then originalMethod
          else
            found = true
            new AdviceAdapter(Opcodes.ASM9, originalMethod, access, name, descriptor):
              override def onMethodExit(opcode: Int): Unit =
                if opcode == Opcodes.ARETURN then
                  val result = newLocal(org.jetbrains.org.objectweb.asm.Type.getType(classOf[Object]))
                  visitVarInsn(Opcodes.ASTORE, result)
                  visitVarInsn(Opcodes.ALOAD, target.elementLocal)
                  visitVarInsn(Opcodes.ALOAD, result)
                  visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    BridgeInternalName,
                    "referenceResolution",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    false
                  )
                  visitTypeInsn(
                    Opcodes.CHECKCAST,
                    "[Lorg/jetbrains/plugins/scala/lang/resolve/ScalaResolveResult;"
                  )
      ,
      ClassReader.EXPAND_FRAMES
    )
    if found then
      val _ = transformedMethods.add(resolveMethodKey(target))
    writer.toByteArray

  private def defineBridge(anchorClass: Class[?]): Class[?] =
    try Class.forName(BridgeClassName, false, anchorClass.getClassLoader)
    catch
      case _: ClassNotFoundException =>
        val path   = BridgeClassName.replace('.', '/') + ".class"
        val stream = getClass.getClassLoader.getResourceAsStream(path)
        if stream == null then throw new IllegalStateException(s"missing bridge resource $path")
        val bytes  =
          try stream.readAllBytes()
          finally stream.close()
        MethodHandles.privateLookupIn(anchorClass, MethodHandles.lookup()).defineClass(bytes)

  private def installBackend(bridge: Class[?]): Unit =
    val declaredTypeBackend: Function[Object, Object]            = element =>
      BundledCompilerBackendDispatcher.declaredType(element)
    val compilerTypeMissing                                      = bridge.getMethod("missingCompilerType").invoke(null)
    val compilerTypeBackend: Function[Object, Object]            = element =>
      BundledCompilerBackendDispatcher.compilerType(element) match
        case CompilerTypeSelection.Current(value) => value
        case CompilerTypeSelection.Missing        => compilerTypeMissing
        case CompilerTypeSelection.FallThrough    => null
    val semanticTypeBackend: BiFunction[Object, Integer, Object] = (element, role) =>
      BundledCompilerBackendDispatcher.semanticType(element, role.intValue())
    val resolveBackend: BiFunction[Object, Object, Object]       = (reference, bundled) =>
      ScalaPluginSemanticBridge.referenceResolution(reference, bundled)
    val _                                                        = bridge.getMethod("install", classOf[Function[?, ?]]).invoke(null, declaredTypeBackend)
    val _                                                        = bridge
      .getMethod("installCompilerTypeBackend", classOf[Function[?, ?]])
      .invoke(null, compilerTypeBackend)
    val _                                                        = bridge
      .getMethod("installSemanticTypeBackend", classOf[BiFunction[?, ?, ?]])
      .invoke(null, semanticTypeBackend)
    val _                                                        = bridge
      .getMethod("installReferenceBackend", classOf[BiFunction[?, ?, ?]])
      .invoke(null, resolveBackend)

  private def uninstallBackend(bridge: Class[?]): Unit =
    val _ = bridge.getMethod("uninstall").invoke(null)

  private def methodKey(target: CompilerBackendShimTarget, method: CompilerBackendShimMethod): String =
    s"${target.internalName}.${method.name}${method.descriptor}"

  private def resolveMethodKey(target: CompilerBackendResolveShimTarget): String =
    s"${target.internalName}.${target.methodName}${target.descriptor}"

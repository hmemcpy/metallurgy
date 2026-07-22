package com.hmemcpy.metallurgy.compilerbackend

import net.bytebuddy.agent.ByteBuddyAgent
import org.jetbrains.org.objectweb.asm.commons.AdviceAdapter
import org.jetbrains.org.objectweb.asm.{ClassReader, ClassVisitor, ClassWriter, Label, MethodVisitor, Opcodes}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement

import java.lang.instrument.{ClassFileTransformer, Instrumentation}
import java.lang.invoke.MethodHandles
import java.security.{MessageDigest, ProtectionDomain}
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.function.{BiFunction, Function}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[metallurgy] object BundledCompilerBackendShim:

  private enum Installation:
    case NotStarted
    case Installing
    case Finished(status: CompilerBackendShimStatus)

  private val TargetClassName          = "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement"
  private val TargetInternalName       = TargetClassName.replace('.', '/')
  private val TargetMethodName         = "type"
  private val TargetDescriptor         = "()Lscala/util/Either;"
  private val CompilerTypeClassName    = "org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$"
  private val CompilerTypeInternalName = CompilerTypeClassName.replace('.', '/')
  private val CompilerTypeMethodName   = "apply"
  private val CompilerTypeDescriptor   = "(Lcom/intellij/psi/PsiElement;)Lscala/Option;"
  private val BridgeClassName          =
    "org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge"
  private val BridgeInternalName       = BridgeClassName.replace('.', '/')

  private val ExpectedClassFingerprint             = "01319ef4eee957a62ee8b2af4e901888e3a858a3c998c172efd45565455772ce"
  private val ExpectedMethodFingerprint            = "31a4333ea0baf3429ff80a60cade86aee60af3e66e313e3a45f69ddfce3f84ab"
  private val ExpectedCompilerTypeClassFingerprint =
    "31463582b1c591ddc175e59edc2c5bb4c7ae589f45e069df1cb0a3b91da6aa3a"

  private val installation = new AtomicReference[Installation](Installation.NotStarted)

  def install(): CompilerBackendShimStatus =
    installation.get() match
      case Installation.Finished(status) => status
      case Installation.Installing       => CompilerBackendShimStatus.Disabled("installation already in progress")
      case Installation.NotStarted       =>
        if installation.compareAndSet(Installation.NotStarted, Installation.Installing) then
          val status = attemptInstall()
          installation.set(Installation.Finished(status))
          status
        else install()

  private def attemptInstall(): CompilerBackendShimStatus =
    try
      val targetClass       = classOf[ScTypeElement]
      val original          = classBytes(targetClass)
      val compilerTypeClass = Class.forName(CompilerTypeClassName, true, targetClass.getClassLoader)
      val compilerTypeBytes = classBytes(compilerTypeClass)
      val semanticTargets   = CompilerBackendShimManifest.targets.map: target =>
        target -> Class.forName(target.className, false, targetClass.getClassLoader)
      val semanticMismatch  = semanticTargets.collectFirst:
        case (target, semanticClass) if semanticCompatibility(target, classBytes(semanticClass)).isLeft =>
          semanticCompatibility(target, classBytes(semanticClass)).swap.toOption.get
      if sha256(compilerTypeBytes) != ExpectedCompilerTypeClassFingerprint then
        CompilerBackendShimStatus.Disabled(
          s"unsupported $CompilerTypeClassName bytecode: class=${sha256(compilerTypeBytes)}"
        )
      else if semanticMismatch.nonEmpty then semanticMismatch.get
      else
        installIfCompatible(original): (classHash, methodHash) =>
          val bridge          = defineBridge(targetClass)
          val instrumentation = ByteBuddyAgent.install()
          if !instrumentation.isRetransformClassesSupported then
            CompilerBackendShimStatus.Disabled("JVM does not support class retransformation")
          else
            installTransformer(
              instrumentation,
              targetClass,
              compilerTypeClass,
              semanticTargets,
              bridge,
              classHash,
              methodHash
            )
    catch case NonFatal(error) => CompilerBackendShimStatus.Disabled(error.toString)

  private[compilerbackend] def installIfCompatible(
      classBytes: Array[Byte]
  )(installCompatible: (String, String) => CompilerBackendShimStatus): CompilerBackendShimStatus =
    compatibility(classBytes) match
      case Left(disabled)                 => disabled
      case Right((classHash, methodHash)) => installCompatible(classHash, methodHash)

  private[compilerbackend] def compatibility(
      classBytes: Array[Byte]
  ): Either[CompilerBackendShimStatus.Disabled, (String, String)] =
    val classHash  = sha256(classBytes)
    val methodHash = methodFingerprint(classBytes)
    if classHash == ExpectedClassFingerprint && methodHash == ExpectedMethodFingerprint then
      Right(classHash -> methodHash)
    else
      Left(
        CompilerBackendShimStatus.Disabled(
          s"unsupported $TargetClassName bytecode: class=$classHash method=$methodHash"
        )
      )

  private[compilerbackend] def supportedClassBytes(): Array[Byte] = classBytes(classOf[ScTypeElement])

  private[compilerbackend] def supportedSemanticClassBytes(target: CompilerBackendShimTarget): Array[Byte] =
    classBytes(Class.forName(target.className, false, classOf[ScTypeElement].getClassLoader))

  private[compilerbackend] def semanticCompatibility(
      target: CompilerBackendShimTarget,
      bytes: Array[Byte]
  ): Either[CompilerBackendShimStatus.Disabled, Unit] =
    val actual = sha256(bytes)
    Either.cond(
      actual == target.classFingerprint,
      (),
      CompilerBackendShimStatus.Disabled(s"unsupported ${target.className} bytecode: class=$actual")
    )

  private def installTransformer(
      instrumentation: Instrumentation,
      targetClass: Class[?],
      compilerTypeClass: Class[?],
      semanticTargets: Vector[(CompilerBackendShimTarget, Class[?])],
      bridge: Class[?],
      classHash: String,
      methodHash: String
  ): CompilerBackendShimStatus =
    val declaredTypeTransformed = new AtomicBoolean(false)
    val compilerTypeTransformed = new AtomicBoolean(false)
    val semanticMethods         = ConcurrentHashMap.newKeySet[String]()
    val transformer             = transformerFor(
      targetClass.getClassLoader,
      declaredTypeTransformed,
      compilerTypeTransformed,
      semanticMethods
    )
    val expectedSemanticMethods = CompilerBackendShimManifest.targets.flatMap: target =>
      target.methods.map(methodKey(target, _))
    var backendInstalled        = false
    var transformerAdded        = false
    try
      installBackend(bridge)
      backendInstalled = true
      instrumentation.addTransformer(transformer, true)
      transformerAdded = true
      instrumentation.retransformClasses((Vector(targetClass, compilerTypeClass) ++ semanticTargets.map(_._2))*)
      if declaredTypeTransformed.get() && compilerTypeTransformed.get() &&
        semanticMethods.containsAll(expectedSemanticMethods.asJava)
      then
        bridge.getMethod("enable").invoke(null)
        CompilerBackendShimStatus.Enabled(classHash, methodHash)
      else
        uninstallBackend(bridge)
        CompilerBackendShimStatus.Disabled("one or more compiler-backend roots were not transformed")
    catch
      case NonFatal(error) =>
        if backendInstalled then uninstallBackend(bridge)
        throw error
    finally
      if transformerAdded then
        val _ = instrumentation.removeTransformer(transformer)

  private def transformerFor(
      targetLoader: ClassLoader,
      declaredTypeTransformed: AtomicBoolean,
      compilerTypeTransformed: AtomicBoolean,
      semanticMethods: java.util.Set[String]
  ): ClassFileTransformer =
    val semanticTargets =
      CompilerBackendShimManifest.targets.iterator.map(target => target.internalName -> target).toMap
    new ClassFileTransformer:
      override def transform(
          loader: ClassLoader,
          className: String,
          classBeingRedefined: Class[?],
          protectionDomain: ProtectionDomain,
          classfileBuffer: Array[Byte]
      ): Array[Byte] =
        if loader != targetLoader then null
        else if className == TargetInternalName then
          val output = transformTarget(classfileBuffer)
          declaredTypeTransformed.set(true)
          output
        else if className == CompilerTypeInternalName then
          val output = transformCompilerType(classfileBuffer)
          compilerTypeTransformed.set(true)
          output
        else
          semanticTargets.get(className) match
            case Some(target) => transformSemanticTarget(classfileBuffer, target, semanticMethods)
            case None         => null

  private def transformSemanticTarget(
      original: Array[Byte],
      target: CompilerBackendShimTarget,
      transformedMethods: java.util.Set[String]
  ): Array[Byte] =
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
          target.methods.find(method => method.name == name && method.descriptor == descriptor) match
            case None         => originalMethod
            case Some(method) =>
              val _ = transformedMethods.add(methodKey(target, method))
              new AdviceAdapter(Opcodes.ASM9, originalMethod, access, name, descriptor):
                override def onMethodEnter(): Unit =
                  val fallback = new Label()
                  visitVarInsn(Opcodes.ALOAD, method.elementLocal)
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
    writer.toByteArray

  private def transformTarget(original: Array[Byte]): Array[Byte] =
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
          if name != TargetMethodName || descriptor != TargetDescriptor then originalMethod
          else
            new AdviceAdapter(Opcodes.ASM9, originalMethod, access, name, descriptor):
              override def onMethodEnter(): Unit =
                val fallback = new Label()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(
                  Opcodes.INVOKESTATIC,
                  BridgeInternalName,
                  "declaredType",
                  "(Ljava/lang/Object;)Ljava/lang/Object;",
                  false
                )
                visitInsn(Opcodes.DUP)
                visitJumpInsn(Opcodes.IFNULL, fallback)
                visitTypeInsn(Opcodes.CHECKCAST, "scala/util/Either")
                visitInsn(Opcodes.ARETURN)
                visitLabel(fallback)
                visitInsn(Opcodes.POP)
      ,
      ClassReader.EXPAND_FRAMES
    )
    writer.toByteArray

  private def transformCompilerType(original: Array[Byte]): Array[Byte] =
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
          if name != CompilerTypeMethodName || descriptor != CompilerTypeDescriptor then originalMethod
          else
            new AdviceAdapter(Opcodes.ASM9, originalMethod, access, name, descriptor):
              override def onMethodEnter(): Unit =
                val fallback = new Label()
                visitVarInsn(Opcodes.ALOAD, 1)
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

  private def defineBridge(targetClass: Class[?]): Class[?] =
    try Class.forName(BridgeClassName, false, targetClass.getClassLoader)
    catch
      case _: ClassNotFoundException =>
        val path   = BridgeClassName.replace('.', '/') + ".class"
        val stream = getClass.getClassLoader.getResourceAsStream(path)
        if stream == null then throw new IllegalStateException(s"missing bridge resource $path")
        val bytes  =
          try stream.readAllBytes()
          finally stream.close()
        MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup()).defineClass(bytes)

  private def installBackend(bridge: Class[?]): Unit =
    val backend: Function[Object, Object]                        = element => BundledCompilerBackendDispatcher.declaredType(element)
    val compilerTypeMissing                                      = bridge.getMethod("missingCompilerType").invoke(null)
    val compilerTypeBackend: Function[Object, Object]            = element =>
      BundledCompilerBackendDispatcher.compilerType(element) match
        case CompilerTypeSelection.Current(value) => value
        case CompilerTypeSelection.Missing        => compilerTypeMissing
        case CompilerTypeSelection.FallThrough    => null
    val semanticTypeBackend: BiFunction[Object, Integer, Object] = (element, role) =>
      BundledCompilerBackendDispatcher.semanticType(element, role.intValue())
    val _                                                        = bridge.getMethod("install", classOf[Function[?, ?]]).invoke(null, backend)
    val _                                                        = bridge
      .getMethod("installCompilerTypeBackend", classOf[Function[?, ?]])
      .invoke(null, compilerTypeBackend)
    val _                                                        = bridge
      .getMethod("installSemanticTypeBackend", classOf[BiFunction[?, ?, ?]])
      .invoke(null, semanticTypeBackend)

  private def uninstallBackend(bridge: Class[?]): Unit =
    val _ = bridge.getMethod("uninstall").invoke(null)

  private def classBytes(targetClass: Class[?]): Array[Byte] =
    val binaryName = targetClass.getName
    val resource   = binaryName.substring(binaryName.lastIndexOf('.') + 1) + ".class"
    val stream     = targetClass.getResourceAsStream(resource)
    if stream == null then throw new IllegalStateException(s"missing class resource $resource")
    try stream.readAllBytes()
    finally stream.close()

  private def methodKey(target: CompilerBackendShimTarget, method: CompilerBackendShimMethod): String =
    s"${target.internalName}.${method.name}${method.descriptor}"

  private def methodFingerprint(classBytes: Array[Byte]): String =
    val writer = new ClassWriter(0)
    writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "MethodFingerprint", null, "java/lang/Object", null)
    new ClassReader(classBytes).accept(
      new ClassVisitor(Opcodes.ASM9):
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          if name == TargetMethodName && descriptor == TargetDescriptor then
            writer.visitMethod(access, name, descriptor, signature, exceptions)
          else null
      ,
      0
    )
    writer.visitEnd()
    sha256(writer.toByteArray)

  private def sha256(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

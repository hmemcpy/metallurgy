package com.hmemcpy.metallurgy.compilerbackend

import net.bytebuddy.agent.ByteBuddyAgent
import org.jetbrains.org.objectweb.asm.commons.AdviceAdapter
import org.jetbrains.org.objectweb.asm.{ClassReader, ClassVisitor, ClassWriter, Label, MethodVisitor, Opcodes}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement

import java.lang.instrument.{ClassFileTransformer, Instrumentation}
import java.lang.invoke.MethodHandles
import java.security.{MessageDigest, ProtectionDomain}
import java.util.HexFormat
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.function.Function
import scala.util.control.NonFatal

private[metallurgy] object BundledCompilerBackendShim:

  private enum Installation:
    case NotStarted
    case Installing
    case Finished(status: CompilerBackendShimStatus)

  private val TargetClassName    = "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement"
  private val TargetInternalName = TargetClassName.replace('.', '/')
  private val TargetMethodName   = "type"
  private val TargetDescriptor   = "()Lscala/util/Either;"
  private val BridgeClassName    =
    "org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge"
  private val BridgeInternalName = BridgeClassName.replace('.', '/')

  private val ExpectedClassFingerprint  = "01319ef4eee957a62ee8b2af4e901888e3a858a3c998c172efd45565455772ce"
  private val ExpectedMethodFingerprint = "31a4333ea0baf3429ff80a60cade86aee60af3e66e313e3a45f69ddfce3f84ab"

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
      val targetClass = classOf[ScTypeElement]
      val original    = classBytes(targetClass)
      installIfCompatible(original): (classHash, methodHash) =>
        val bridge          = defineBridge(targetClass)
        val instrumentation = ByteBuddyAgent.install()
        if !instrumentation.isRetransformClassesSupported then
          CompilerBackendShimStatus.Disabled("JVM does not support class retransformation")
        else installTransformer(instrumentation, targetClass, bridge, classHash, methodHash)
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

  private def installTransformer(
      instrumentation: Instrumentation,
      targetClass: Class[?],
      bridge: Class[?],
      classHash: String,
      methodHash: String
  ): CompilerBackendShimStatus =
    val transformed      = new AtomicBoolean(false)
    val transformer      = transformerFor(targetClass.getClassLoader, transformed)
    var backendInstalled = false
    var transformerAdded = false
    try
      installBackend(bridge)
      backendInstalled = true
      instrumentation.addTransformer(transformer, true)
      transformerAdded = true
      instrumentation.retransformClasses(targetClass)
      if transformed.get() then
        bridge.getMethod("enable").invoke(null)
        CompilerBackendShimStatus.Enabled(classHash, methodHash)
      else
        uninstallBackend(bridge)
        CompilerBackendShimStatus.Disabled(s"$TargetClassName was not transformed")
    catch
      case NonFatal(error) =>
        if backendInstalled then uninstallBackend(bridge)
        throw error
    finally
      if transformerAdded then
        val _ = instrumentation.removeTransformer(transformer)

  private def transformerFor(targetLoader: ClassLoader, transformed: AtomicBoolean): ClassFileTransformer =
    new ClassFileTransformer:
      override def transform(
          loader: ClassLoader,
          className: String,
          classBeingRedefined: Class[?],
          protectionDomain: ProtectionDomain,
          classfileBuffer: Array[Byte]
      ): Array[Byte] =
        if loader != targetLoader || className != TargetInternalName then null
        else
          val output = transformTarget(classfileBuffer)
          transformed.set(true)
          output

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
    val backend: Function[Object, Object] = element => BundledCompilerBackendDispatcher.declaredType(element)
    val _                                 = bridge.getMethod("install", classOf[Function[?, ?]]).invoke(null, backend)

  private def uninstallBackend(bridge: Class[?]): Unit =
    val _ = bridge.getMethod("uninstall").invoke(null)

  private def classBytes(targetClass: Class[?]): Array[Byte] =
    val resource = targetClass.getSimpleName + ".class"
    val stream   = targetClass.getResourceAsStream(resource)
    if stream == null then throw new IllegalStateException(s"missing class resource $resource")
    try stream.readAllBytes()
    finally stream.close()

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

package com.hmemcpy.metallurgy.compilerbackend

import com.intellij.openapi.module.Module
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.jetbrains.org.objectweb.asm.{ClassReader, ClassVisitor, MethodVisitor, Opcodes}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.jar.JarFile
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

private[metallurgy] final case class InstalledScalaPluginArtifact(fileName: String, byteSize: Long, sha256: String)
private[metallurgy] final case class InstalledScalaPluginMethod(
    name: String,
    descriptor: String,
    access: Int,
    genericSignature: Option[String] = None
)
private[metallurgy] final case class InstalledScalaPluginClass(
    internalName: String,
    superName: Option[String],
    interfaces: Vector[String],
    access: Int,
    methods: Vector[InstalledScalaPluginMethod],
    genericSignature: Option[String] = None
)
private[metallurgy] final case class InstalledScalaPluginDescriptorFact(
    kind: String,
    implementation: Option[String],
    ordinal: Int = 0
)
private[metallurgy] final case class InstalledScalaPluginSurface(
    artifact: InstalledScalaPluginArtifact,
    classes: Vector[InstalledScalaPluginClass],
    descriptorFacts: Vector[InstalledScalaPluginDescriptorFact],
    unresolved: Vector[String]
)

/** IntelliJ-side compatibility seam for bundled Scala-plugin semantics.
  *
  * Public settings and PSI interfaces are used directly. Structural/reflected access is confined here where the bundled
  * plugin exposes no sufficient extension point or cross-classloader-safe interface.
  */
object ScalaPluginSemanticBridge:

  def installedPsiSurface(): Either[String, InstalledScalaPluginSurface] =
    InstalledScalaPluginSurfaceScanner.scan(classOf[org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement])

  def install(): CompilerBackendShimStatus =
    BundledCompilerBackendShim.install()

  private lazy val bundledClassLoader: ClassLoader =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType").getClassLoader

  private lazy val compilerSettingsProfileModule =
    scalaModule("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettingsProfile$")

  private lazy val compilerSettingsModule =
    scalaModule("org.jetbrains.plugins.scala.project.settings.ScalaCompilerSettings$")

  // --- ModuleExt.scalaMinorVersion ---

  private lazy val moduleExtClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.project.package$ModuleExt", true, bundledClassLoader)

  private lazy val moduleExtCtor = moduleExtClass.getConstructors.head

  private lazy val scalaMinorVersionMethod: Method =
    moduleExtClass.getMethod("scalaMinorVersion")

  private lazy val optionIsEmptyMethod: Method =
    Class.forName("scala.Option", true, bundledClassLoader).getMethod("isEmpty")

  private lazy val optionGetMethod: Method =
    Class.forName("scala.Option", true, bundledClassLoader).getMethod("get")

  def optionValue(option: AnyRef): Option[AnyRef] =
    if option == null || optionIsEmptyMethod.invoke(option).asInstanceOf[Boolean] then None
    else Some(optionGetMethod.invoke(option).asInstanceOf[AnyRef])

  private lazy val scalaVersionMinorMethod: Method =
    Class.forName("org.jetbrains.plugins.scala.ScalaVersion", true, bundledClassLoader).getMethod("minor")

  def getScalaVersion(module: Module): String =
    try
      val ext          = moduleExtCtor.newInstance(module)
      val option       = scalaMinorVersionMethod.invoke(ext)
      if option == null then return null
      val isEmpty      = optionIsEmptyMethod.invoke(option).asInstanceOf[Boolean]
      if isEmpty then return null
      val scalaVersion = optionGetMethod.invoke(option)
      scalaVersionMinorMethod.invoke(scalaVersion).asInstanceOf[String]
    catch case _: Throwable => null

  // --- Compiler settings ---

  def additionalCompilerOptions(module: Module): Seq[String] =
    val state = compilerSettingsState(compilerProfileFor(module))
    Option(state.getClass.getField("additionalCompilerOptions").get(state).asInstanceOf[Array[String]])
      .fold(Seq.empty)(_.toSeq)

  def compilerOptions(module: Module): Seq[String] =
    val settings = compilerSettingsFor(compilerProfileFor(module))
    val values   = settings.getClass
      .getMethod("getOptionsAsStrings", java.lang.Boolean.TYPE)
      .invoke(settings, Boolean.box(true))
    scalaStrings(values)

  def setAdditionalCompilerOptions(module: Module, options: Seq[String]): Unit =
    val profile = compilerProfileFor(module)
    val state   = compilerSettingsState(profile)
    state.getClass.getField("additionalCompilerOptions").set(state, options.toArray)
    val rebuilt = compilerSettingsModule.getClass
      .getMethod("fromState", state.getClass)
      .invoke(compilerSettingsModule, state)
    val _       = profile.getClass.getMethods
      .find(method => method.getName == "setSettings" && method.getParameterCount == 1)
      .getOrElse(throw new NoSuchMethodException("ScalaCompilerSettingsProfile.setSettings"))
      .invoke(profile, rebuilt)

  private def compilerProfileFor(module: Module): AnyRef =
    compilerSettingsProfileModule.getClass
      .getMethod("forModule", classOf[Module])
      .invoke(compilerSettingsProfileModule, module)
      .asInstanceOf[AnyRef]

  private def compilerSettingsState(profile: AnyRef): AnyRef =
    val settings = compilerSettingsFor(profile)
    settings.getClass.getMethod("toState").invoke(settings).asInstanceOf[AnyRef]

  private def compilerSettingsFor(profile: AnyRef): AnyRef =
    profile.getClass.getMethod("getSettings").invoke(profile).asInstanceOf[AnyRef]

  private def scalaStrings(values: AnyRef): Seq[String] =
    val iterator = values.getClass.getMethod("iterator").invoke(values)
    val hasNext  = iterator.getClass.getMethod("hasNext")
    val next     = iterator.getClass.getMethod("next")
    Iterator
      .continually(iterator)
      .takeWhile(value => hasNext.invoke(value).asInstanceOf[Boolean])
      .map(value => next.invoke(value).asInstanceOf[String])
      .toSeq

  private def scalaModule(className: String): AnyRef =
    Class
      .forName(className, true, bundledClassLoader)
      .getField("MODULE$")
      .get(null)
      .asInstanceOf[AnyRef]

  // --- CompilerType ---

  private lazy val compilerTypeModuleClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$", true, bundledClassLoader)

  private lazy val compilerTypeModuleInstance: AnyRef =
    compilerTypeModuleClass.getField("MODULE$").get(null).asInstanceOf[AnyRef]

  private lazy val compilerTypeApply: Method =
    compilerTypeModuleClass.getMethod("apply", classOf[PsiElement])

  private lazy val scalaOptionModule: AnyRef =
    scalaModule("scala.Option$")

  private lazy val scalaOptionApply: Method =
    scalaOptionModule.getClass.getMethod("apply", classOf[Object])

  private lazy val compilerTypeUpdate: Method =
    compilerTypeModuleClass.getMethod(
      "update",
      classOf[PsiElement],
      Class.forName("scala.Option", true, bundledClassLoader)
    )

  def getCompilerType(element: PsiElement): String =
    optionValue(compilerTypeApply.invoke(compilerTypeModuleInstance, element).asInstanceOf[AnyRef])
      .fold(null)(_.asInstanceOf[String])

  def setCompilerType(element: PsiElement, value: String): Unit =
    val compilerType = scalaOptionApply.invoke(scalaOptionModule, value).asInstanceOf[AnyRef]
    val _            = compilerTypeUpdate.invoke(compilerTypeModuleInstance, element, compilerType)

  def clearCompilerType(element: PsiElement): Unit =
    setCompilerType(element, null)

  // --- CompilerType.Topic and Listener ---

  private lazy val compilerTypeTopic: com.intellij.util.messages.Topic[?] =
    compilerTypeModuleClass
      .getMethod("Topic")
      .invoke(compilerTypeModuleInstance)
      .asInstanceOf[com.intellij.util.messages.Topic[?]]

  private lazy val listenerClass: Class[?] =
    Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$Listener", true, bundledClassLoader)

  def subscribeToCompilerTypeRequests(project: Project, owner: Disposable)(callback: PsiElement => Unit): Unit =
    val handler  = new InvocationHandler:
      override def invoke(proxy: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
        if method.getName == "onCompilerTypeRequest" && args != null && args.nonEmpty then
          callback(args(0).asInstanceOf[PsiElement])
        null
    val listener = Proxy.newProxyInstance(listenerClass.getClassLoader, Array(listenerClass), handler)
    project.getMessageBus
      .connect(owner)
      .subscribe(
        compilerTypeTopic.asInstanceOf[com.intellij.util.messages.Topic[AnyRef]],
        listener.asInstanceOf[AnyRef]
      )

  // --- ScalaProjectSettings ---

  def usesCompilerTypes(project: Project): Boolean =
    val settings = ScalaProjectSettings.getInstance(project)
    settings.isCompilerHighlightingScala3 && settings.isUseCompilerTypes

  def clearScalaTypeCacheForElement(project: Project, element: PsiElement): Unit =
    val managerModuleClass =
      Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager$", true, bundledClassLoader)
    val managerModule      = managerModuleClass.getField("MODULE$").get(null)
    val manager            = managerModuleClass.getMethod("instance", classOf[Project]).invoke(managerModule, project)
    val _                  = manager.getClass.getMethod("clearOnScalaElementChange", classOf[PsiElement]).invoke(manager, element)

  def clearScalaTypeCaches(project: Project, element: PsiElement): Unit =
    clearScalaTypeCacheForElement(project, element)
    bumpAnyScalaPsiChange()

  /** Invalidate all Scala type caches project-wide. */
  def invalidateScalaTypeCaches(): Unit =
    bumpAnyScalaPsiChange()

  private[metallurgy] def scalaPsiModificationCount: Long =
    anyScalaPsiChangeTracker.getClass
      .getMethod("getModificationCount")
      .invoke(anyScalaPsiChangeTracker)
      .asInstanceOf[Long]

  private def bumpAnyScalaPsiChange(): Unit =
    val _ = anyScalaPsiChangeTracker.getClass.getMethod("incModificationCount").invoke(anyScalaPsiChangeTracker)

  private def anyScalaPsiChangeTracker: AnyRef =
    val modTrackerClass = Class.forName("org.jetbrains.plugins.scala.caches.ModTracker$", true, bundledClassLoader)
    val modTracker      = modTrackerClass.getField("MODULE$").get(null)
    modTrackerClass.getMethod("anyScalaPsiChange").invoke(modTracker)

private[metallurgy] object InstalledScalaPluginSurfaceScanner:
  private val IncludedPrefixes = Vector(
    "org/jetbrains/plugins/scala/lang/psi/api/",
    "org/jetbrains/plugins/scala/lang/psi/impl/",
    "org/jetbrains/plugins/scala/lang/psi/stubs/"
  )
  private val IncludedClasses  = Set(
    "org/jetbrains/plugins/scala/lang/parser/ScalaElementType$",
    "org/jetbrains/plugins/scala/lang/parser/ScalaParserDefinition$",
    "org/jetbrains/plugins/scala/lang/parser/Scala3ParserDefinition$"
  )

  def scan(anchor: Class[?]): Either[String, InstalledScalaPluginSurface] =
    root(anchor)
      .toRight(s"no code source for ${anchor.getName}")
      .flatMap: path =>
        Try:
          val (classBytes, descriptors, artifactBytes, artifactSize) =
            if Files.isDirectory(path) then directoryEntries(path)
            else
              val bytes          = Files.readAllBytes(path)
              val (classes, xml) = jarEntries(path)
              (classes, xml, bytes, bytes.length.toLong)
          val artifact                                               = InstalledScalaPluginArtifact(
            path.getFileName.toString,
            artifactSize,
            MessageDigest.getInstance("SHA-256").digest(artifactBytes).map(b => f"${b & 0xff}%02x").mkString
          )
          val parsed                                                 = classBytes.map((name, bytes) => name -> readClass(bytes))
          val classes                                                = parsed.collect { case (_, Right(clazz)) => clazz }.sortBy(_.internalName)
          val unresolved                                             = Vector.newBuilder[String]
          parsed.collect { case (name, Left(reason)) => s"class:$name:$reason" }.foreach(unresolved += _)
          val descriptorResults                                      = descriptors.zipWithIndex.map((xml, index) => readDescriptor(xml, index))
          val facts                                                  = descriptorResults.collect { case Right(value) => value }.flatten.sortBy(_.ordinal)
          descriptorResults.collect { case Left(reason) => reason }.foreach(unresolved += _)
          facts
            .filter(_.implementation.forall(name => !classes.exists(_.internalName == name)))
            .foreach: fact =>
              unresolved += s"descriptor:${fact.ordinal}:${fact.kind}:target is absent or unscanned:${fact.implementation.getOrElse("<missing>")}"
          if classes.isEmpty then unresolved += "no matching Scala PSI classes"
          if descriptors.isEmpty then unresolved += "META-INF/scala-plugin-common.xml is absent"
          else if facts.isEmpty then unresolved += "plugin descriptor contains no stub declarations"
          InstalledScalaPluginSurface(artifact, classes, facts, unresolved.result().sorted)
        .toEither.left.map(error => s"cannot inspect installed Scala plugin: ${error.getMessage}")

  private def root(anchor: Class[?]): Option[Path] =
    Option(anchor.getProtectionDomain)
      .flatMap(d => Option(d.getCodeSource))
      .flatMap(s => Try(Path.of(s.getLocation.toURI)).toOption)
      .orElse:
        val className = anchor.getName.replace('.', '/') + ".class"
        Option(anchor.getResource("/" + className)).flatMap: url =>
          Try:
            if url.getProtocol == "jar" then
              val value = url.getFile
              Path.of(java.net.URI.create(value.substring(0, value.indexOf('!'))))
            else if url.getProtocol == "file" then
              var value = Path.of(url.toURI)
              for _ <- 0 until className.count(_ == '/') + 1 do value = value.getParent
              value
            else null
          .toOption.filter(_ != null)

  private def included(name: String): Boolean =
    name.endsWith(".class") && (IncludedPrefixes.exists(name.startsWith) || IncludedClasses(name.stripSuffix(".class")))

  private def jarEntries(path: Path): (Vector[(String, Array[Byte])], Vector[String]) =
    Using.resource(new JarFile(path.toFile)): jar =>
      val entries     = jar.entries.asScala.toVector
      val classes     = entries
        .filter(e => !e.isDirectory && included(e.getName))
        .map(e => e.getName -> Using.resource(jar.getInputStream(e))(_.readAllBytes()))
      val descriptors = entries
        .filter(_.getName == "META-INF/scala-plugin-common.xml")
        .map(e =>
          new String(Using.resource(jar.getInputStream(e))(_.readAllBytes()), java.nio.charset.StandardCharsets.UTF_8)
        )
      classes -> descriptors

  private def directoryEntries(path: Path): (Vector[(String, Array[Byte])], Vector[String], Array[Byte], Long) =
    val stream = Files.walk(path)
    try
      val files   = stream.iterator.asScala.filter(Files.isRegularFile(_)).toVector
      val entries = files.map(p => path.relativize(p).toString.replace('\\', '/') -> Files.readAllBytes(p)).sortBy(_._1)
      val digest  = MessageDigest.getInstance("SHA-256")
      entries.foreach: (name, bytes) =>
        val encoded = name.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(encoded.length).array()); digest.update(encoded)
        digest.update(java.nio.ByteBuffer.allocate(8).putLong(bytes.length.toLong).array()); digest.update(bytes)
      (
        entries.filter((name, _) => included(name)),
        files
          .filter(p => path.relativize(p).toString.replace('\\', '/') == "META-INF/scala-plugin-common.xml")
          .map(Files.readString),
        digest.digest(),
        entries.map(_._2.length.toLong).sum
      )
    finally stream.close()

  private[metallurgy] def readClass(bytes: Array[Byte]): Either[String, InstalledScalaPluginClass] = Try:
    var result: InstalledScalaPluginClass = null
    val methods                           = Vector.newBuilder[InstalledScalaPluginMethod]
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
          result = InstalledScalaPluginClass(
            name,
            Option(superName),
            Option(interfaces).fold(Vector.empty)(_.toVector.sorted),
            access,
            Vector.empty,
            Option(signature)
          )
        override def visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String,
            exceptions: Array[String]
        ): MethodVisitor =
          methods += InstalledScalaPluginMethod(name, descriptor, access, Option(signature)); null
      ,
      ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
    )
    result.copy(methods = methods.result().sortBy(m => (m.name, m.descriptor, m.access)))
  .toEither.left.map(error => Option(error.getMessage).getOrElse(error.getClass.getName))

  private[metallurgy] def readDescriptor(
      xml: String,
      documentOrdinal: Int = 0
  ): Either[String, Vector[InstalledScalaPluginDescriptorFact]] = Try:
    val factory  = javax.xml.parsers.DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    factory.setXIncludeAware(false); factory.setExpandEntityReferences(false)
    val builder  = factory.newDocumentBuilder()
    builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler:
      override def error(exception: org.xml.sax.SAXParseException): Unit      = throw exception
      override def fatalError(exception: org.xml.sax.SAXParseException): Unit = throw exception
    )
    val document = builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)))
    Vector("stubElementTypeHolder" -> "class", "stubIndex" -> "implementation").flatMap: (tag, attribute) =>
      val nodes = document.getElementsByTagName(tag)
      (0 until nodes.getLength).map: index =>
        val raw = Option(nodes.item(index).getAttributes.getNamedItem(attribute)).map(_.getNodeValue).filter(_.nonEmpty)
        InstalledScalaPluginDescriptorFact(tag, raw.map(_.replace('.', '/')), documentOrdinal * 1000000 + index)
  .toEither.left.map(error =>
    s"descriptor:$documentOrdinal:malformed XML:${Option(error.getMessage).getOrElse(error.getClass.getName)}"
  )

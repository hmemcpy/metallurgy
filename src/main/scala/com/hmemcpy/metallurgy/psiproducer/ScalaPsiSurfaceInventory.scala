package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.*
import org.jetbrains.org.objectweb.asm.Opcodes
import scala.util.Try

private[metallurgy] enum SurfaceFactKind:
  case Element, Token, Factory, PublicAccessor, Stub, Serializer, Index, Navigation, ParserProduction, Class, Method
private[metallurgy] enum SurfaceClassification:
  case SyntaxContract, SemanticOnly, MutationRefactoring, Derived, Helper, NotApplicable, Unclassified
private[metallurgy] enum FactStatus:
  case Available
  case Unresolved(reason: String)
  case Unsupported(reason: String)
private[metallurgy] final case class ScalaPsiSurfaceRow(
    id: String,
    kind: SurfaceFactKind,
    ownerId: Option[String],
    status: FactStatus,
    classification: SurfaceClassification,
    evidence: Vector[String] = Vector.empty
)
private[metallurgy] final case class ScalaPsiSurfaceInventory(
    rows: Vector[ScalaPsiSurfaceRow],
    artifact: Option[InstalledScalaPluginArtifact] = None
):
  private lazy val encoded        = ScalaPsiSurfaceInventory.serialize(this)
  lazy val fingerprint: String    = CanonicalByteEncoder.sha256Hex(encoded)
  def canonicalBytes: Array[Byte] = encoded.clone()

  def withCatalogCapabilities(catalog: Scala3PsiProductionCatalog): ScalaPsiSurfaceInventory =
    val compatibleComposites = catalog.productions
      .flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
      .filter(_.targetRequirement == TargetRequirement.Compatible)
    val ownedTargets         = compatibleComposites
      .map(_.targetSurfaceId)
      .distinct
      .filterNot(id => rows.exists(_.id == id))
      .map(id =>
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Element,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract,
          Vector("capability-probed compatible PSI target")
        )
      )
    val ownedAccessors       = compatibleComposites
      .flatMap(composite => composite.accessors.map(composite.targetSurfaceId -> _))
      .distinct
      .filterNot((_, obligation) => rows.exists(_.id == obligation.surfaceId))
      .map: (owner, obligation) =>
        val available = for
          separator <- obligation.surfaceId.indexOf('#') match
                         case -1    => None
                         case value => Some(value)
          className  = obligation.surfaceId.substring(0, separator).replace('/', '.')
          signature  = obligation.surfaceId.substring(separator + 1)
          clazz     <- Try(Class.forName(className, false, getClass.getClassLoader)).toOption
          method    <-
            clazz.getMethods.find(method =>
              s"${method.getName}${org.jetbrains.org.objectweb.asm.Type.getMethodDescriptor(method)}" == signature
            )
        yield method
        ScalaPsiSurfaceRow(
          obligation.surfaceId,
          obligation.surfaceKind,
          Some(owner),
          if available.nonEmpty then FactStatus.Available
          else FactStatus.Unresolved("compatible PSI accessor is absent"),
          SurfaceClassification.SyntaxContract,
          Vector("capability-probed compatible PSI accessor")
        )
    copy(rows = rows ++ ownedTargets ++ ownedAccessors)

private[metallurgy] object ScalaPsiSurfaceInventory:
  def installed(): Either[String, ScalaPsiSurfaceInventory] =
    ScalaPluginSemanticBridge.installedPsiSurface().map(from)

  def from(surface: InstalledScalaPluginSurface): ScalaPsiSurfaceInventory =
    val classes                                                          = surface.classes.map(clazz => clazz.internalName -> clazz).toMap
    val psiRoot                                                          = "org/jetbrains/plugins/scala/lang/psi/api/ScalaPsiElement"
    val stubRoots                                                        = Set(
      "com/intellij/psi/stubs/StubElement",
      "com/intellij/psi/stubs/NamedStub",
      "com/intellij/psi/stubs/PsiFileStub",
      "com/intellij/psi/stubs/StubBase",
      "com/intellij/psi/stubs/IStubElementType",
      "com/intellij/psi/stubs/IStubFileElementType",
      "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType"
    )
    def derives(name: String, roots: Set[String]): Boolean               =
      val visited = scala.collection.mutable.Set.empty[String]
      val pending = scala.collection.mutable.Stack(name)
      var result  = false
      while pending.nonEmpty && !result do
        val current = pending.pop()
        if roots(current) then result = true
        else if visited.add(current) then
          classes.get(current).foreach(clazz => pending.pushAll(clazz.superName.toVector ++ clazz.interfaces))
      result
    def methodReturn(method: InstalledScalaPluginMethod): Option[String] =
      Try(org.jetbrains.org.objectweb.asm.Type.getReturnType(method.descriptor)).toOption
        .filter(_.getSort == org.jetbrains.org.objectweb.asm.Type.OBJECT)
        .map(_.getInternalName)
    val classRows                                                        = surface.classes.flatMap: clazz =>
      val public   = (clazz.access & Opcodes.ACC_PUBLIC) != 0
      val concrete = (clazz.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0
      val psi      = derives(clazz.internalName, Set(psiRoot))
      val api      = public && psi
      val stub     = derives(clazz.internalName, stubRoots)
      val native   = concrete && clazz.methods
        .filter(_.name == "<init>")
        .flatMap(method => Try(org.jetbrains.org.objectweb.asm.Type.getArgumentTypes(method.descriptor)).toOption)
        .flatten
        .exists(argument =>
          argument.getSort == org.jetbrains.org.objectweb.asm.Type.OBJECT &&
            (argument.getInternalName == "com/intellij/lang/ASTNode" || derives(argument.getInternalName, stubRoots))
        )
      val evidence = Vector(
        s"access:${clazz.access}",
        s"super:${clazz.superName.getOrElse("")}",
        s"interfaces:${clazz.interfaces.mkString(",")}",
        s"signature:${clazz.genericSignature.getOrElse("")}",
        s"constructors:${clazz.methods.filter(_.name == "<init>").map(_.descriptor).mkString(",")}"
      )
      val typeRow  = Some(
        ScalaPsiSurfaceRow(
          clazz.internalName,
          if stub then SurfaceFactKind.Stub else if psi then SurfaceFactKind.Element else SurfaceFactKind.Class,
          None,
          FactStatus.Available,
          if psi && native then SurfaceClassification.SyntaxContract
          else if api || (psi && concrete) then SurfaceClassification.Derived
          else if stub then SurfaceClassification.Derived
          else SurfaceClassification.Helper,
          evidence
        )
      )
      val methods  = clazz.methods
        .filter(m => (m.access & Opcodes.ACC_PUBLIC) != 0)
        .filter(m => (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0)
        .filterNot(m =>
          m.name == "<init>" || m.name == "<clinit>" || m.name.endsWith("_$eq") || m.name.startsWith("set")
        )
        .map: method =>
          val id         = s"${clazz.internalName}#${method.name}${method.descriptor}"
          val arguments  = Try(org.jetbrains.org.objectweb.asm.Type.getArgumentTypes(method.descriptor)).toOption
          val zeroArgs   = arguments.exists(_.isEmpty)
          val returnsPsi =
            methodReturn(method).exists(name => derives(name, Set(psiRoot)) || name.startsWith("com/intellij/psi/")) ||
              method.genericSignature.exists(signature =>
                signature.contains("Lorg/jetbrains/plugins/scala/lang/psi/api/") ||
                  signature.contains("Lcom/intellij/psi/")
              )
          val navigation = psi && zeroArgs && returnsPsi &&
            (method.name == "getNavigationElement" || method.name == "navigationElement")
          val serializer = stub && Set("serialize", "deserialize", "getExternalId")(method.name) &&
            ((method.name == "getExternalId" && zeroArgs) || (method.name != "getExternalId" && arguments.exists(
              _.nonEmpty
            )))
          val accessor   = api && zeroArgs && returnsPsi
          val evidence   = Vector(
            s"access:${method.access}",
            s"descriptor:${method.descriptor}",
            s"signature:${method.genericSignature.getOrElse("")}"
          )
          ScalaPsiSurfaceRow(
            id,
            if navigation then SurfaceFactKind.Navigation
            else if serializer then SurfaceFactKind.Serializer
            else if accessor then SurfaceFactKind.PublicAccessor
            else SurfaceFactKind.Method,
            Some(clazz.internalName),
            FactStatus.Available,
            if accessor then SurfaceClassification.SyntaxContract
            else SurfaceClassification.Derived,
            evidence
          )
      typeRow.toVector ++ methods
    val descriptorRows                                                   = surface.descriptorFacts.map: fact =>
      val kind  = if fact.kind == "stubIndex" then SurfaceFactKind.Index else SurfaceFactKind.Factory
      val bound = fact.implementation.exists(classes.contains)
      val id    =
        s"descriptor:${fact.ordinal}:${fact.kind}:${fact.implementation.getOrElse("unresolved")}"
      ScalaPsiSurfaceRow(
        id,
        kind,
        None,
        if bound then FactStatus.Available
        else FactStatus.Unresolved("registration target is absent or unscanned"),
        if kind == SurfaceFactKind.Index then SurfaceClassification.SyntaxContract else SurfaceClassification.Derived,
        Vector(
          s"registration:${fact.kind}",
          s"ordinal:${fact.ordinal}",
          s"target:${fact.implementation.getOrElse("")}"
        )
      )
    val unresolvedRows                                                   = surface.unresolved.map(reason =>
      ScalaPsiSurfaceRow(
        s"unresolved:$reason",
        SurfaceFactKind.Stub,
        None,
        FactStatus.Unresolved(reason),
        SurfaceClassification.SyntaxContract
      )
    )
    val rows                                                             = (classRows ++ descriptorRows ++ unresolvedRows).distinct.sortBy(canonicalRow)
    ScalaPsiSurfaceInventory(rows, Some(surface.artifact))

  def serialize(inventory: ScalaPsiSurfaceInventory): Array[Byte] =
    val e = CanonicalByteEncoder()
    e.tag(1)
    inventory.artifact match
      case None           => e.tag(0)
      case Some(artifact) => e.tag(1); e.string(artifact.fileName); e.long(artifact.byteSize); e.string(artifact.sha256)
    e.sequence(inventory.rows.distinct.sortBy(canonicalRow))(writeRow(_, e))
    e.result()

  private def canonicalRow(row: ScalaPsiSurfaceRow): String =
    val e = CanonicalByteEncoder(); writeRow(row, e)
    java.util.Base64.getEncoder.encodeToString(e.result())

  private def writeRow(row: ScalaPsiSurfaceRow, e: CanonicalByteEncoder): Unit =
    e.string(row.id); e.tag(row.kind.ordinal)
    row.ownerId.fold(e.tag(0))(owner => { e.tag(1); e.string(owner) })
    row.status match
      case FactStatus.Available           => e.tag(1)
      case FactStatus.Unresolved(reason)  => e.tag(2); e.string(reason)
      case FactStatus.Unsupported(reason) => e.tag(3); e.string(reason)
    e.tag(row.classification.ordinal)
    e.sequence(row.evidence)(e.string)

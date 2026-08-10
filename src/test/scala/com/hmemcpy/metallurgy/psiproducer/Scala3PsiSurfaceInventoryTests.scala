package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test
import org.jetbrains.org.objectweb.asm.Opcodes

private[psiproducer] trait Scala3PsiSurfaceInventoryTests extends Scala3PsiProductionCatalogTestSupport:
  @Test def surfaceInventoryUsesStructuralAncestryAndConservativeAccessors(): Unit =
    val root      = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/api/ScalaPsiElement",
      Some("java/lang/Object"),
      Vector("com/intellij/psi/PsiElement"),
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
      Vector.empty
    )
    val child     = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/api/ScChild",
      Some("java/lang/Object"),
      Vector(root.internalName),
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
      Vector(
        InstalledScalaPluginMethod("child", s"()L${root.internalName};", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("unit", "()V", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("argument", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("staticValue", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
        InstalledScalaPluginMethod("syntheticValue", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC)
      )
    )
    val helper    = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/impl/Helper",
      Some("java/lang/Object"),
      Vector.empty,
      Opcodes.ACC_PUBLIC,
      Vector.empty
    )
    val inventory = ScalaPsiSurfaceInventory.from(
      InstalledScalaPluginSurface(
        InstalledScalaPluginArtifact("plugin.jar", 1L, "hash"),
        Vector(helper, child, root),
        Vector.empty,
        Vector.empty
      )
    )
    assertEquals(SurfaceFactKind.Class, inventory.rows.find(_.id == helper.internalName).get.kind)
    assertEquals(SurfaceClassification.Helper, inventory.rows.find(_.id == helper.internalName).get.classification)
    assertEquals(SurfaceClassification.Derived, inventory.rows.find(_.id == child.internalName).get.classification)
    val methods   = inventory.rows.filter(_.ownerId.contains(child.internalName))
    assertTrue(
      methods.exists(row => row.id.contains("#child") && row.classification == SurfaceClassification.SyntaxContract)
    )
    assertTrue(methods.exists(row => row.id.contains("#unit") && row.kind == SurfaceFactKind.Method))
    assertTrue(methods.exists(row => row.id.contains("#argument") && row.kind == SurfaceFactKind.Method))
    assertFalse(methods.exists(_.id.contains("#staticValue")))
    assertFalse(methods.exists(_.id.contains("#syntheticValue")))
    assertTrue(inventory.rows.find(_.id == child.internalName).get.evidence.exists(_.startsWith("interfaces:")))

  @Test def descriptorAndMalformedBinaryEvidenceFailClosed(): Unit =
    val descriptor =
      """<idea-plugin><extensions><stubElementTypeHolder class="example.Holder"/><stubIndex implementation="example.Index"/></extensions></idea-plugin>"""
    val facts      = InstalledScalaPluginSurfaceScanner.readDescriptor(descriptor).toOption.get
    assertEquals(
      Vector("example/Holder", "example/Index"),
      facts.flatMap(_.implementation)
    )
    assertTrue(InstalledScalaPluginSurfaceScanner.readDescriptor("<idea-plugin>").isLeft)
    assertTrue(InstalledScalaPluginSurfaceScanner.readClass(Array[Byte](1, 2, 3)).isLeft)

  @Test def surfaceCanonicalizationDistinguishesOptionalAndTextBoundaries(): Unit =
    val base  = ScalaPsiSurfaceRow(
      "surface\u0000id\uD800",
      SurfaceFactKind.Element,
      None,
      FactStatus.Available,
      SurfaceClassification.Derived,
      Vector("a\u0000b", "c")
    )
    val one   = ScalaPsiSurfaceInventory(Vector(base))
    val two   = ScalaPsiSurfaceInventory(Vector(base.copy(ownerId = Some(""))))
    val three = ScalaPsiSurfaceInventory(Vector(base.copy(evidence = Vector("a", "b\u0000c"))))
    assertNotEquals(one.fingerprint, two.fingerprint)
    assertNotEquals(one.fingerprint, three.fingerprint)

  @Test def surfaceInventoryCanonicalizesRawBinaryFactsAndBindsArtifact(): Unit =
    val api      = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/api/ScSynthetic",
      Some("java/lang/Object"),
      Vector("com/intellij/psi/PsiElement"),
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
      Vector(
        InstalledScalaPluginMethod("bc", "()V", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("b", "(Lc;)V", Opcodes.ACC_PUBLIC)
      )
    )
    val artifact = InstalledScalaPluginArtifact("scalaCommunity.jar", 17L, "abc")
    val facts    = Vector(
      InstalledScalaPluginDescriptorFact("stubIndex", Some("example.Index")),
      InstalledScalaPluginDescriptorFact("stubElementTypeHolder", None)
    )
    val forward  =
      ScalaPsiSurfaceInventory.from(InstalledScalaPluginSurface(artifact, Vector(api), facts, Vector("malformed")))
    val reverse  = ScalaPsiSurfaceInventory.from(
      InstalledScalaPluginSurface(
        artifact,
        Vector(api.copy(methods = api.methods.reverse)),
        facts.reverse,
        Vector("malformed")
      )
    )
    assertArrayEquals(forward.canonicalBytes, reverse.canonicalBytes)
    assertEquals(forward.fingerprint, reverse.fingerprint)
    assertTrue(forward.rows.exists(_.status == FactStatus.Unresolved("registration target is absent or unscanned")))
    assertTrue(forward.rows.exists(_.status == FactStatus.Unresolved("malformed")))
    val changed  = ScalaPsiSurfaceInventory.from(
      InstalledScalaPluginSurface(artifact.copy(byteSize = 18L), Vector(api), facts, Vector("malformed"))
    )
    assertNotEquals(forward.fingerprint, changed.fingerprint)
    val bytes    = forward.canonicalBytes
    bytes(0) = (bytes(0) + 1).toByte
    assertEquals(forward.fingerprint, CanonicalByteEncoder.sha256Hex(forward.canonicalBytes))

  @Test def installedSurfaceHasStableExactCategories(): Unit =
    val first  = ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
    val second = ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
    assertTrue(first.artifact.exists(a => a.fileName.nonEmpty && a.byteSize > 0 && a.sha256.length == 64))
    Vector(
      SurfaceFactKind.Element,
      SurfaceFactKind.PublicAccessor,
      SurfaceFactKind.Stub,
      SurfaceFactKind.Index,
      SurfaceFactKind.Navigation
    )
      .foreach(kind => assertTrue(s"missing $kind", first.rows.exists(_.kind == kind)))
    assertFalse(first.rows.exists(_.status.isInstanceOf[FactStatus.Unresolved]))
    assertTrue(
      first.rows.exists(row =>
        row.kind == SurfaceFactKind.Factory && row.evidence.contains("registration:stubElementTypeHolder")
      )
    )
    assertArrayEquals(first.canonicalBytes, second.canonicalBytes)
    assertEquals(first.fingerprint, second.fingerprint)

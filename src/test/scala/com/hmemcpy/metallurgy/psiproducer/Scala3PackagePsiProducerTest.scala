package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{IndexSink, StubIndexKey}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.{PlatformTestUtil, ServiceContainerUtil}
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.stubs.ScPackagingStub
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3PackagePsiProducerTest extends Scala3CompatTestCase:

  def testReadyPhysicalPackageUsesNativePsiAndReparsesAndStubs(): Unit =
    val source    = "package example.syntax\n"
    val installed = Scala3ParserPreparationLifecycle.get(getProject)
    installed.dispose()
    val preparer  = new DeferredPreparer
    var files     = Vector.empty[VirtualFile]
    val lifecycle = new Scala3ParserPreparationLifecycle(
      getProject,
      preparer,
      _ => files,
      new PlatformRecordingActivation(getProject)
    )
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[Scala3ParserPreparationLifecycle],
      lifecycle,
      getTestRootDisposable
    )
    val _         = lifecycle.prepare(getModule)
    val pending   = myFixture.addFileToProject("src/PackageCase.scala", source)
    files = Vector(pending.getVirtualFile)
    preparer.complete(0, new TestParserBridge(Some((bridge, request) => packageSnapshot(request, bridge))))
    PlatformTestUtil.waitWithEventsDispatching(
      "package parser activation",
      () => lifecycle.stateFor(getModule).isInstanceOf[ParserPreparationState.Ready],
      10000
    )
    assertPlan(lifecycle.parserFor(getModule).get, pending.getVirtualFile.getUrl, source)

    val file = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(file, source, "syntax")
    val copy = file.copy().asInstanceOf[com.intellij.psi.PsiFile]
    assertPackage(copy, source, "syntax")

    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(16, 22, "changed")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(reparsed, "package example.changed\n", "changed")

  private def assertPackage(file: com.intellij.psi.PsiFile, text: String, name: String): Unit =
    assertEquals(text, file.getText)
    val leaves    = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText)
    assertEquals(Vector("package", " ", "example", ".", name, "\n"), leaves)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    assertNotNull(packaging)
    val reference = packaging.reference.get
    val qualifier = reference.qualifier.get
    assertEquals(
      "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.packaging.ScPackagingImpl",
      packaging.getClass.getName
    )
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl", reference.getClass.getName)
    assertEquals(ScalaElementType.PACKAGING, packaging.getNode.getElementType)
    assertEquals(ScalaElementType.REFERENCE, reference.getNode.getElementType)
    assertEquals(ScalaElementType.REFERENCE, qualifier.getNode.getElementType)
    assertEquals("package", packaging.keyword.getText)
    assertEquals(s"example.$name", packaging.packageName)
    assertEquals("", packaging.parentPackageName)
    assertEquals(name, reference.refName)
    assertEquals("example", qualifier.refName)
    assertSame(packaging, reference.getParent)
    assertSame(reference, qualifier.getParent)
    assertSame(file, packaging.getContainingFile)
    Vector(packaging, reference, qualifier).foreach: element =>
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    assertEquals(
      Vector(reference),
      packaging.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    assertEquals(
      Vector(qualifier),
      reference.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    val stubTree  = file.asInstanceOf[PsiFileImpl].calcStubTree
    val stub      = stubTree.getPlainList.asScala.collectFirst { case value: ScPackagingStub => value }.orNull
    assertNotNull(stub)
    assertEquals(s"example.$name", stub.packageName)
    assertEquals("", stub.parentPackageName)
    assertFalse(stub.isExplicit)
    assertEquals(ScalaElementType.PACKAGING, stub.getElementType)
    val indexed   = Vector.newBuilder[String]
    ScalaElementType.PACKAGING.indexStub(
      stub,
      new IndexSink:
        override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
          indexed += value.toString
    )
    assertEquals(Vector(s"example.$name", "example"), indexed.result())

  private def assertPlan(prepared: PreparedScala3Parser, uri: String, source: String): Unit =
    val request   = Scala3ParserRequest(ParserSourceUri.from(uri).toOption.get, source, prepared.compilerOptions)
    val snapshot  = prepared.bridge.parse(request).fold(error => throw new AssertionError(error.toString), identity)
    val evidence  = ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val runtime   = CompilerRuntimeInventory
      .from(snapshot)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val aggregate = AggregatedCompilerProductionInventory
      .aggregate(Vector(runtime))
      .fold(error => throw new AssertionError(error.toString), identity)
    val catalog   = PreparedProductionCatalog
      .prepareRuntimeSubset(prepared.catalog, runtime, aggregate, prepared.surfaces)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val plan      = WholeFileProductionPlanner
      .plan(snapshot, evidence, catalog)
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(3, plan.composites.size)

  private def packageSnapshot(
      request: Scala3ParserRequest,
      bridge: TestParserBridge
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    val source = request.sourceText
    if !source.matches("package [^.\\n]+\\.[^.\\n]+\\n") then Left(Scala3ParserError.Closed)
    else
      val dot      = source.indexOf('.')
      val end      = source.length - 1
      val position = (from: Int, point: Int, to: Int) =>
        ParserNodePosition.Positioned(
          PcSourceRange(from, to),
          point,
          ParserPositionProvenance.SourceDerived
        )
      val nodes    = Vector(
        ParserSyntaxNode(
          1,
          "PackageDef",
          Vector(
            ParserSyntaxField("pid", ParserFieldValue.Node(2), Some(ParserDeclaredShape.Node)),
            ParserSyntaxField(
              "stats",
              ParserFieldValue.Repeated(Vector.empty),
              Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
            )
          ),
          position(0, dot + 1, end),
          Vector.empty
        ),
        ParserSyntaxNode(
          2,
          "Select",
          Vector(
            ParserSyntaxField("qualifier", ParserFieldValue.Node(3), Some(ParserDeclaredShape.Node)),
            ParserSyntaxField(
              "name",
              ParserFieldValue.Name(source.substring(dot + 1, end)),
              Some(ParserDeclaredShape.Name)
            )
          ),
          position(8, dot + 1, end),
          Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("pid"))))
        ),
        ParserSyntaxNode(
          3,
          "Ident",
          Vector(
            ParserSyntaxField(
              "name",
              ParserFieldValue.Name(source.substring(8, dot)),
              Some(ParserDeclaredShape.Name)
            )
          ),
          position(8, 8, dot),
          Vector(ParserNodeOccurrence(2, Vector(ParserFieldPathSegment.NamedField("qualifier"))))
        )
      )
      Right(
        ParserSyntaxSnapshot(
          request.sourceUri,
          source,
          ParserSyntaxSnapshot.digest(source),
          source.length,
          request.compilerOptions,
          1,
          nodes,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          bridge.capabilities,
          bridge.identity
        )
      )

package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.IndexSink
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.stubs.StubIndexKey
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.ServiceContainerUtil
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.ScExportsHolder
import org.jetbrains.plugins.scala.lang.psi.api.base.ScEnd
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScExportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.stubs.ScExportStmtStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportStmtStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScPackagingStub
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}
import scala.jdk.CollectionConverters.*

private[psiproducer] trait Scala3PackagePsiPackageLifecycleTests extends Scala3PackagePsiProducerTestSupport:
  private def assertPackageBodiesUseDirectNativePsiAndFailClosedOnRecovery(): Unit =
    val source  =
      """package braced { /* header */
        |  import alpha.braced.Member; export alpha.braced.Member
        |  import alpha.braced.Other; export alpha.braced.Other
        |  package nested { import alpha.nested.Member; export alpha.nested.Member /* nested tail */ }
        |  /* braced tail */
        |}
        |package outer:
        |  import alpha.outer.Member
        |  package inner:
        |    export alpha.inner.Member
        |  end inner
        |end outer
        |package empty { /* body trivia */ }
        |package first:
        |  import alpha.first.Member
        |  export alpha.first.Member
        |  import alpha.first.Other; export alpha.first.Other
        |  // trailing indented
        |package peer:
        |  export alpha.peer.Member
        |""".stripMargin
    val pending = codeInsightFixture.addFileToProject("src/PackageLayoutCase.scala", source)

    def assertLayout(file: com.intellij.psi.PsiFile): Unit =
      assertEquals(source, file.getText)
      assertEquals(source, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val failure  = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.isEmpty)
      val packages = PsiTreeUtil.findChildrenOfType(file, classOf[ScPackaging]).asScala.toVector
      val byFull   = packages.map(value => value.fullPackageName -> value).toMap
      val braced   = byFull("braced")
      val nested   = byFull("braced.nested")
      val outer    = byFull("outer")
      val inner    = byFull("outer.inner")
      val empty    = byFull("empty")
      val first    = byFull("first")
      val peer     = byFull("peer")
      val ends     = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnd]).asScala.toVector

      assertEquals(7, packages.size)
      assertEquals(
        Vector(braced, outer, empty, first, peer),
        file.getChildren.collect { case value: ScPackaging => value }.toVector
      )
      assertSame(braced, nested.getParent)
      assertSame(outer, inner.getParent)
      assertEquals(Vector(nested), braced.packagings.toVector)
      assertEquals(Vector(inner), outer.packagings.toVector)
      assertTrue(packages.forall(_.isExplicit))
      assertTrue(Vector(braced, nested, empty).forall(_.isEnclosedByBraces))
      assertTrue(Vector(outer, inner, first, peer).forall(_.isEnclosedByColon))
      assertEquals(Vector("{", "{", ":", ":", "{", ":", ":"), packages.map(_.findExplicitMarker.get.getText))
      assertTrue(Vector(braced, nested, empty).forall(_.getLBrace.nonEmpty))
      assertTrue(
        Vector(braced, nested, empty)
          .map(packaging =>
            s"${packaging.fullPackageName}:${packaging.getTextRange}:${packaging.getRBrace.map(_.getText)}"
          )
          .mkString(", "),
        Vector(braced, nested, empty).forall(_.getRBrace.nonEmpty)
      )
      assertTrue(Vector(outer, inner, first, peer).forall(_.getColon.nonEmpty))
      packages.flatMap(_.getColon).foreach(value => assertEquals(ScalaTokenTypes.tCOLON, value.getNode.getElementType))
      assertEquals(
        Vector(
          ("braced", "", "braced"),
          ("nested", "braced", "braced.nested"),
          ("outer", "", "outer"),
          ("inner", "outer", "outer.inner"),
          ("empty", "", "empty"),
          ("first", "", "first"),
          ("peer", "", "peer")
        ),
        packages.map(packaging => (packaging.packageName, packaging.parentPackageName, packaging.fullPackageName))
      )
      assertEquals(Vector("inner", "outer"), ends.map(_.getName))
      ends.foreach: end =>
        assertEquals(s"end ${end.getName}", end.getText)
        assertEquals(ScalaElementType.END_STMT, end.getNode.getElementType)
        assertEquals(ScalaTokenType.EndKeyword, end.keyword.getNode.getElementType)
        assertEquals("end", end.keyword.getText)
        assertEquals(end.getName, end.tag.getText)
        assertSame(end, end.getReference)
        assertSame(end, end.getElement)
        assertEquals(end.tag.getTextRangeInParent, end.getRangeInElement)
        assertTrue(end.isSoft)
        assertEquals("ScEnd", end.getCanonicalText)
        assertSame(end.getParent, end.begin.get)
        assertSame(end, end.getParent.getLastChild)
        val resolved = end.resolve()
        assertNotNull(resolved)
        assertSame(end, resolved.getContext)
        assertFalse(end.isReferenceTo(resolved))
        assertSame(end, end.bindToElement(resolved))
      assertEquals(Vector(ends.find(_.getName == "inner").get), inner.end.toVector)
      assertEquals(Vector(ends.find(_.getName == "outer").get), outer.end.toVector)
      assertTrue(Vector(braced, nested, empty, first, peer).forall(_.end.isEmpty))

      val imports         = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
      val exports         = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
      assertEquals(Vector(braced, braced, nested, outer, first, first), imports.map(_.getParent))
      assertEquals(Vector(braced, braced, nested, inner, first, first, peer), exports.map(_.getParent))
      assertEquals(
        Vector("braced", "braced", "braced.nested", "outer.inner", "first", "first", "peer"),
        exports.flatMap(_.topLevelQualifier)
      )
      assertEquals(Vector(imports(0), imports(1)), braced.getImportStatements.toVector)
      assertEquals(Vector(exports(0), exports(1)), braced.asInstanceOf[ScExportsHolder].getExportStatements.toVector)
      assertEquals(
        source.substring(source.indexOf("package first"), source.indexOf("package peer")),
        first.getText
      )
      val trailingComment = PsiTreeUtil.collectElements(
        file,
        element => element.getFirstChild == null && element.getText == "// trailing indented"
      )
      assertEquals(Vector(first), trailingComment.toVector.map(_.getParent))
      assertEquals(
        """ /* header */
          |  import alpha.braced.Member; export alpha.braced.Member
          |  import alpha.braced.Other; export alpha.braced.Other
          |  package nested { import alpha.nested.Member; export alpha.nested.Member /* nested tail */ }
          |  /* braced tail */
          |""".stripMargin,
        braced.bodyText
      )
      assertEquals(" import alpha.nested.Member; export alpha.nested.Member /* nested tail */ ", nested.bodyText)
      assertEquals(
        """
          |  import alpha.outer.Member
          |  package inner:
          |    export alpha.inner.Member
          |  end inner
          |end outer""".stripMargin,
        outer.bodyText
      )
      assertEquals("\n    export alpha.inner.Member\n  end inner", inner.bodyText)
      assertEquals(" /* body trivia */ ", empty.bodyText)
      assertEquals(
        """
          |  import alpha.first.Member
          |  export alpha.first.Member
          |  import alpha.first.Other; export alpha.first.Other
          |  // trailing indented
          |""".stripMargin,
        first.bodyText
      )
      assertEquals("\n  export alpha.peer.Member\n", peer.bodyText)
      assertTrue(empty.getImportStatements.isEmpty)
      assertTrue(empty.asInstanceOf[ScExportsHolder].getExportStatements.isEmpty)
      val semicolons      =
        PsiTreeUtil.collectElements(file, element => element.getFirstChild == null && element.getText == ";")
      assertEquals(Vector(braced, braced, nested, first), semicolons.toVector.map(_.getParent))
      Vector(
        "/* header */"      -> braced,
        "/* nested tail */" -> nested,
        "/* braced tail */" -> braced,
        "/* body trivia */" -> empty
      ).foreach: (text, owner) =>
        val comment =
          PsiTreeUtil.collectElements(file, element => element.getFirstChild == null && element.getText == text)
        assertEquals(s"$text parent", Vector(owner), comment.toVector.map(_.getParent))

      val composites = packages ++ ends ++ imports ++ exports
      composites.foreach: element =>
        assertSame(file, element.getContainingFile)
        assertSame(getProject, element.getProject)
        assertSame(element, element.getNode.getPsi)
        assertSame(element, element.getNavigationElement)
        assertEquals(
          element.getText,
          source.substring(element.getTextRange.getStartOffset, element.getTextRange.getEndOffset)
        )

      val stubs          = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.toVector
      val packageStubs   = stubs.collect { case value: ScPackagingStub => value }
      assertEquals(7, packageStubs.size)
      assertEquals(
        Vector(
          ("braced", "", true),
          ("nested", "braced", true),
          ("outer", "", true),
          ("inner", "outer", true),
          ("empty", "", true),
          ("first", "", true),
          ("peer", "", true)
        ),
        packageStubs.map(stub => (stub.packageName, stub.parentPackageName, stub.isExplicit))
      )
      assertEquals(6, stubs.count(_.isInstanceOf[ScImportStmtStub]))
      assertEquals(7, stubs.count(_.isInstanceOf[ScExportStmtStub]))
      assertTrue(
        stubs.forall(stub =>
          (!stub.isInstanceOf[ScImportStmtStub] && !stub.isInstanceOf[ScExportStmtStub]) ||
            stub.getParentStub.isInstanceOf[ScPackagingStub]
        )
      )
      val enumerator     = new TestStringEnumerator
      val sink           = new ByteArrayOutputStream
      val output         = new StubOutputStream(sink, enumerator)
      val nestedStub     = packageStubs(1)
      ScalaElementType.PACKAGING.serialize(nestedStub, output)
      output.flush()
      val nestedCopy     = ScalaElementType.PACKAGING.deserialize(
        new StubInputStream(new ByteArrayInputStream(sink.toByteArray), enumerator),
        new PsiFileStubImpl(null)
      )
      assertEquals(nestedStub.packageName, nestedCopy.packageName)
      assertEquals(nestedStub.parentPackageName, nestedCopy.parentPackageName)
      assertEquals(nestedStub.isExplicit, nestedCopy.isExplicit)
      val packageFqns    = packageStubs.map: stub =>
        val values = Vector.newBuilder[String]
        ScalaElementType.PACKAGING.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
              assertSame(ScalaIndexKeys.PACKAGE_FQN_KEY, _indexKey)
              values += value.toString
        )
        values.result()
      assertEquals(
        Vector(
          Vector("braced"),
          Vector("braced.nested"),
          Vector("outer"),
          Vector("outer.inner"),
          Vector("empty"),
          Vector("first"),
          Vector("peer")
        ),
        packageFqns
      )
      val exportPackages = stubs
        .collect { case stub: ScExportStmtStub => stub }
        .map: stub =>
          val values = Vector.newBuilder[String]
          ScalaElementType.ExportStatement.indexStub(
            stub,
            new IndexSink:
              override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
                assertSame(ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY, _indexKey)
                values += value.toString
          )
          values.result()
      assertEquals(
        Vector("braced", "braced", "braced.nested", "outer.inner", "first", "first", "peer").map(Vector(_)),
        exportPackages
      )
    val file                                               = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertLayout(file)
    assertLayout(file.copy().asInstanceOf[com.intellij.psi.PsiFile])
    val chainedSource                                      =
      "package qualified.name; package chained\nimport alpha.chained.Member\n"
    val chainedPending                                     = codeInsightFixture.addFileToProject("src/ChainedPackageLayout.scala", chainedSource)
    val chainedFile                                        = PsiManager.getInstance(getProject).findFile(chainedPending.getVirtualFile)
    val chainedPackages                                    = PsiTreeUtil
      .findChildrenOfType(chainedFile, classOf[ScPackaging])
      .asScala
      .toVector
    val chainedImport                                      = PsiTreeUtil.findChildOfType(chainedFile, classOf[ScImportStmt])
    assertEquals(chainedSource, chainedFile.getText)
    assertEquals(Vector("qualified.name", "qualified.name.chained"), chainedPackages.map(_.fullPackageName))
    assertEquals(
      Vector(chainedPackages.head),
      chainedFile.getChildren.collect { case value: ScPackaging => value }.toVector
    )
    assertSame(chainedPackages.head, chainedPackages(1).getParent)
    assertSame(chainedPackages(1), chainedImport.getParent)
    assertTrue(chainedPackages.forall(!_.isExplicit))
    assertTrue(chainedPackages.forall(_.findExplicitMarker.isEmpty))
    val pointer                                            = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(
        PsiTreeUtil.findChildrenOfType(file, classOf[ScPackaging]).asScala.find(_.fullPackageName == "outer.inner").get
      )
    val document                                           = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText(
          source.replace(
            "package empty { /* body trivia */ }",
            "package empty:\n  import alpha.empty.Member\nend empty"
          )
        )
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertNotNull(pointer.getElement)
    assertEquals("outer.inner", pointer.getElement.fullPackageName)
    val edited                                             = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(3, PsiTreeUtil.findChildrenOfType(edited, classOf[ScEnd]).size)

    val transitionSource                          = "package transition\nimport alpha.transition.Member\n"
    val transitionPending                         = codeInsightFixture.addFileToProject("src/PackageLayoutTransition.scala", transitionSource)
    val transitionDocument                        = FileDocumentManager.getInstance.getDocument(transitionPending.getVirtualFile)
    def transitionPackages(): Vector[ScPackaging] =
      PsiTreeUtil
        .findChildrenOfType(
          PsiManager.getInstance(getProject).findFile(transitionPending.getVirtualFile),
          classOf[ScPackaging]
        )
        .asScala
        .toVector
    def replaceTransition(text: String): Unit     =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit = transitionDocument.setText(text)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(transitionDocument)
    val initialTransition                         = transitionPackages().head
    val transitionPointer                         = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(initialTransition)
    assertFalse(initialTransition.isExplicit)
    assertTrue(initialTransition.findExplicitMarker.isEmpty)
    replaceTransition(
      "package transition { import alpha.transition.Member; package nested { export alpha.nested.Member } }\n"
    )
    val bracedTransition                          = transitionPackages()
    assertEquals(Vector("transition", "transition.nested"), bracedTransition.map(_.fullPackageName))
    assertTrue(bracedTransition.forall(_.isEnclosedByBraces))
    assertSame(bracedTransition.head, bracedTransition(1).getParent)
    assertNotNull(transitionPointer.getElement)
    replaceTransition(
      """package transition:
        |  import alpha.transition.Member
        |  package nested:
        |    export alpha.nested.Member
        |  end nested
        |end transition
        |""".stripMargin
    )
    val colonTransition                           = transitionPackages()
    assertEquals(Vector("transition", "transition.nested"), colonTransition.map(_.fullPackageName))
    assertTrue(colonTransition.forall(_.isEnclosedByColon))
    assertEquals(2, PsiTreeUtil.findChildrenOfType(colonTransition.head.getContainingFile, classOf[ScEnd]).size)
    assertNotNull(transitionPointer.getElement)
    replaceTransition(transitionSource)
    val finalTransition                           = transitionPackages()
    assertEquals(1, finalTransition.size)
    assertFalse(finalTransition.head.isExplicit)
    assertNotNull(transitionPointer.getElement)

    Vector(
      "package broken { import a.b\n",
      "package broken import a.b\n",
      "package broken:\nimport a.b\n",
      "package a; import b.c; package d\n",
      "package a:\n  import b.c\nend\n",
      "package a:\n  import b.c\nend wrong\n",
      "package unsupported { class Parent(value: Int); import a.b; class Definition extends Parent(1) }\n",
      "package unsupported:\n  class Parent(value: Int)\n  import a.b\n  object Template extends Parent(1)\n",
      "package unsupported:\n  extension (value: Int) def increment = value + 1\n"
    ).zipWithIndex.foreach: (invalid, index) =>
      val recovered = codeInsightFixture.addFileToProject(s"src/RecoveredPackageLayout$index.scala", invalid)
      val psi       = PsiManager.getInstance(getProject).findFile(recovered.getVirtualFile).asInstanceOf[PsiFileImpl]
      assertEquals(invalid, psi.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScPackaging]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScImportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScExportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScEnd]).isEmpty)
      assertTrue(psi.calcStubTree.getPlainList.asScala.drop(1).isEmpty)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(recovered.getVirtualFile, ParserSyntaxSnapshot.digest(invalid))
          .nonEmpty
      )

  def testReadyPhysicalPackageUsesNativePsiAndReparsesAndStubs(): Unit =
    assertPackageBodiesUseDirectNativePsiAndFailClosedOnRecovery()
    val source     = "package example.syntax\n"
    val installed  = Scala3ParserPreparationLifecycle.get(getProject)
    installed.dispose()
    val preparer   = new DeferredPreparer
    var files      = Vector.empty[VirtualFile]
    val activation = new PlatformRecordingActivation(getProject)
    val lifecycle  = new Scala3ParserPreparationLifecycle(
      getProject,
      preparer,
      _ => files,
      activation
    )
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[Scala3ParserPreparationLifecycle],
      lifecycle,
      getTestRootDisposable
    )
    val _          = lifecycle.prepare(super.getModule)
    val pending    = codeInsightFixture.addFileToProject("src/PackageCase.scala", source)
    files = Vector(pending.getVirtualFile)
    preparer.complete(0, new TestParserBridge(Some((bridge, request) => uncoveredSnapshot(request, bridge))))
    awaitReady(lifecycle, "fail-closed package parser activation")
    val failClosed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile).asInstanceOf[PsiFileImpl]
    assertEquals(source, failClosed.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(failClosed, classOf[ScPackaging]).isEmpty)
    assertTrue(failClosed.calcStubTree.getPlainList.asScala.drop(1).isEmpty)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertTrue(indexedPackages("example.syntax").isEmpty)

    val _ = lifecycle.prepare(super.getModule)
    preparer.complete(1, new TestParserBridge(Some((bridge, request) => packageSnapshot(request, bridge))))
    awaitReady(lifecycle, "covered package parser reactivation")
    assertEquals(3, activation.batchCount)
    assertFalse(failClosed.isValid)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertPlan(lifecycle.parserFor(super.getModule).get, pending.getVirtualFile.getUrl, source)

    val file = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(file, source, "syntax")
    assertEquals(
      Vector(pending.getVirtualFile),
      indexedPackages("example.syntax").map(_.getContainingFile.getVirtualFile).distinct
    )
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

  protected final def assertPackagePath(
      file: com.intellij.psi.PsiFile,
      text: String,
      segments: Vector[String],
      persistence: Boolean
  ): Unit =
    assertEquals(text, file.getText)
    val leaves    = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    assertNotNull(packaging)
    val reference = packaging.reference.get
    assertStablePath(reference, segments)
    assertEquals(segments.mkString("."), packaging.packageName)
    assertEquals("", packaging.parentPackageName)
    assertEquals(
      Vector(reference),
      packaging.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    assertSame(file, packaging.getContainingFile)
    assertSame(getProject, packaging.getProject)
    assertSame(packaging, packaging.getNode.getPsi)
    assertSame(packaging, packaging.getNavigationElement)
    if persistence then
      val stub    = file
        .asInstanceOf[PsiFileImpl]
        .calcStubTree
        .getPlainList
        .asScala
        .collectFirst { case value: ScPackagingStub => value }
        .orNull
      assertNotNull(stub)
      assertEquals(segments.mkString("."), stub.packageName)
      assertEquals("", stub.parentPackageName)
      assertFalse(stub.isExplicit)
      val indexed = Vector.newBuilder[String]
      ScalaElementType.PACKAGING.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.PACKAGE_FQN_KEY, _indexKey)
            indexed += value.toString
      )
      assertEquals(
        segments.indices.reverse
          .map(index => segments.take(index + 1).map(_.stripPrefix("`").stripSuffix("`")).mkString("."))
          .toVector,
        indexed.result()
      )

  private def indexedPackages(fqn: String): Vector[ScPackaging] =
    StubIndex
      .getElements(
        ScalaIndexKeys.PACKAGE_FQN_KEY,
        fqn,
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScPackaging]
      )
      .asScala
      .toVector

  private def awaitReady(lifecycle: Scala3ParserPreparationLifecycle, label: String): Unit =
    PlatformTestUtil.waitWithEventsDispatching(
      label,
      () => lifecycle.stateFor(super.getModule).isInstanceOf[ParserPreparationState.Ready],
      10000
    )

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

  private def uncoveredSnapshot(
      request: Scala3ParserRequest,
      bridge: TestParserBridge
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    val source = request.sourceText
    Right(
      ParserSyntaxSnapshot(
        request.sourceUri,
        source,
        ParserSyntaxSnapshot.digest(source),
        source.length,
        request.compilerOptions,
        1,
        Vector(
          ParserSyntaxNode(
            1,
            "Uncovered",
            Vector.empty,
            ParserNodePosition.Positioned(
              PcSourceRange(0, source.length),
              0,
              ParserPositionProvenance.SourceDerived
            ),
            Vector.empty
          )
        ),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        bridge.capabilities,
        bridge.identity,
        Vector.empty
      )
    )

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
          bridge.identity,
          Vector.empty
        )
      )

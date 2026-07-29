package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.{ASTNode, PsiBuilderFactory}
import com.intellij.openapi.vfs.{StandardFileSystems, VirtualFile}
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import com.intellij.psi.{PsiElement, PsiFile}
import org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.stubs.ScFileStub

final class Scala3DotcFileElementType
    extends IStubFileElementType[ScFileStub](Scala3DotcFileElementType.DebugName, Scala3DotcLanguage.INSTANCE):

  override def getExternalId: String = Scala3DotcFileElementType.ExternalId

  override def getStubVersion: Int =
    Math.addExact(Scala3ParserDefinition.FileNodeType.getStubVersion, Scala3DotcFileElementType.SchemaVersion)

  override def shouldBuildStubFor(file: VirtualFile): Boolean =
    file.getFileSystem.getProtocol != StandardFileSystems.JAR_PROTOCOL

  override def getBuilder: DefaultStubBuilder = new DefaultStubBuilder:
    override protected def createStubForFile(file: PsiFile): PsiFileStubImpl[? <: PsiFile] =
      file.getViewProvider.getPsi(getLanguage) match
        case scalaFile: ScalaFile => new Scala3DotcFileStub(scalaFile, Scala3DotcFileElementType.this)
        case _                    => PsiFileStubImpl(file)

  override def serialize(stub: ScFileStub, dataStream: StubOutputStream): Unit = ()

  override def deserialize(
      dataStream: StubInputStream,
      parentStub: StubElement[? <: PsiElement]
  ): ScFileStub = new Scala3DotcFileStub(null, this)

  override def indexStub(stub: ScFileStub, sink: IndexSink): Unit = ()

  override protected def doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode =
    val source = chameleon.getChars.toString
    DotcTreeSource.extractionFor(source) match
      case Some(extraction) =>
        val builder = PsiBuilderFactory
          .getInstance()
          .createBuilder(psi.getProject, chameleon, null, Scala3DotcLanguage.INSTANCE, chameleon.getChars)
        DotcPsiProducer.parse(this, builder, extraction)
        builder.getTreeBuilt.getFirstChildNode
      case None             => super.doParseContents(chameleon, psi)

private object Scala3DotcFileElementType:
  val ExternalId    = "metallurgy.scala3.file"
  val DebugName     = "METALLURGY_SCALA3_FILE"
  val SchemaVersion = 1

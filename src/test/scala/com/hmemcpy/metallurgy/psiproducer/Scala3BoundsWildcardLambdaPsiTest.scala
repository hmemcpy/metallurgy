package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{
  IndexSink,
  ObjectStubSerializer,
  PsiFileStub,
  SerializationManagerEx,
  Stub,
  StubIndexKey,
  StubTree
}
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScContextBound,
  ScInfixTypeElement,
  ScTypeLambdaTypeElement,
  ScWildcardTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScTypeAlias, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameterClause, ScParameters, ScTypeParam}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScTrait}
import org.junit.Assert.{assertEquals, assertNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3BoundsWildcardLambdaPsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  def testBoundsWildcardsHigherKindsAndTypeLambdasUseExactPhysicalPsi(): Unit =
    val source =
      """trait High
        |trait Low extends High
        |trait Bounded[+A >: Low <: High, F[_]]
        |trait Anonymous[Coll[_]]
        |class ClassBounded[A <: High]
        |class ContextBounded[A: Ordering]
        |type Wild = List[? >: Low <: High]
        |type AliasParameter[A] = A
        |type Lambda = [X >: Low <: High] =>> List[X]
        |opaque type Hidden = High
        |opaque type Alias >: Low <: High = High
        |""".stripMargin
    val file   = physical("BoundsWildcardLambda1.scala", source)

    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

    val wildcard = PsiTreeUtil.findChildOfType(file, classOf[ScWildcardTypeElement])
    assertEquals("? >: Low <: High", wildcard.getText)
    assertEquals(Some("Low"), wildcard.lowerTypeElement.map(_.getText))
    assertEquals(Some("High"), wildcard.upperTypeElement.map(_.getText))
    wildcard.lowerTypeElement.foreach(bound => assertSame(wildcard, bound.getParent))
    wildcard.upperTypeElement.foreach(bound => assertSame(wildcard, bound.getParent))

    val lambda = PsiTreeUtil.findChildOfType(file, classOf[ScTypeLambdaTypeElement])
    assertEquals("[X >: Low <: High] =>> List[X]", lambda.getText)
    assertEquals(Vector("X"), lambda.typeParameters.map(_.name).toVector)
    assertEquals(Some("List[X]"), lambda.resultTypeElement.map(_.getText))

    val parameters = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).asScala.toVector
    val bounded    = parameters
      .find(_.getText.startsWith("+A"))
      .getOrElse(throw new AssertionError(s"missing A in ${parameters.map(_.getText)}"))
    assertEquals(
      parameters
        .map(parameter =>
          s"${parameter.getTextRange}:${parameter.getText}:${parameter.name}:" +
            parameter.getNode
              .getChildren(null)
              .map(child => s"${child.getElementType}:${child.getText}")
              .mkString("[", ", ", "]")
        )
        .mkString("parameters: ", "; ", ""),
      "A",
      bounded.name
    )
    assertTrue(bounded.isCovariant)
    assertEquals("A", bounded.nameId.getText)
    assertEquals("+", bounded.nameId.getPrevSibling.getText)
    assertEquals(Some("Low"), bounded.lowerTypeElement.map(_.getText))
    assertEquals(Some("High"), bounded.upperTypeElement.map(_.getText))
    val higher     = parameters.find(_.name == "F").get
    assertEquals(Vector("_"), higher.typeParameters.map(_.getText).toVector)
    assertTrue(higher.typeParameters.forall(parameter => !parameter.name.matches("_\\$[0-9]+")))
    val anonymous  = parameters.find(parameter => parameter.getText == "_" && parameter.getParent.getText == "[_]").get
    assertEquals("_", anonymous.name)
    assertEquals("_", anonymous.nameId.getText)
    assertTrue(!anonymous.name.matches("_\\$[0-9]+"))

    val aliases = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAlias]).asScala.toVector
    val opaque  = aliases.find(_.name == "Alias").get.asInstanceOf[ScTypeAliasDefinition]
    assertEquals(
      opaque.getNode.getChildren(null).map(child => s"${child.getElementType}:${child.getText}").mkString(", "),
      Some("Low"),
      opaque.lowerTypeElement.map(_.getText)
    )
    assertEquals(Some("High"), opaque.upperTypeElement.map(_.getText))
    assertEquals("High", opaque.aliasedTypeElement.get.getText)

  def testContextBoundsOwnExactTypesAndNamedAsForms(): Unit =
    val source =
      """import scala.language.experimental.modularity
        |trait High
        |trait Evidence[A]
        |trait BinaryEvidence[A, B]
        |trait KindEvidence[F[_]]
        |def unnamed[A: Evidence](value: A): A = value
        |def named[A: Evidence as evidence](value: A): Evidence[A] = evidence
        |def aggregate[A: {Evidence, [X] =>> BinaryEvidence[X, X]}](value: A): A = value
        |def higher[F[_]: KindEvidence](value: F[High]): F[High] = value
        |""".stripMargin
    val file   = physical("ContextBounds1.scala", source)

    val bounds = PsiTreeUtil.findChildrenOfType(file, classOf[ScContextBound]).asScala.toVector
    assertEquals(
      Vector("Evidence", "Evidence as evidence", "Evidence", "[X] =>> BinaryEvidence[X, X]", "KindEvidence"),
      bounds.map(_.getText)
    )
    assertEquals(Vector(None, Some("evidence"), None, None, None), bounds.map(_.nameOpt))
    bounds.foreach(bound => assertSame(bound, bound.typeElement.getParent))
    bounds.foreach(bound => assertEquals(Some(bound.getParent), bound.parentTypeParam))

    val parameters = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).asScala.toVector
    val owners     = parameters.filter(_.contextBounds.nonEmpty)
    assertEquals(Vector("A", "A", "A", "F"), owners.map(_.name))
    assertEquals(Vector(1, 1, 2, 1), owners.map(_.contextBounds.size))

  def testBoundsAndHigherKindsCoverEveryAdmittedDefinitionOwner(): Unit =
    val source =
      """trait High
        |trait Low extends High
        |class ClassOwner[-A >: Low <: High, F[_]]
        |trait TraitOwner[+A >: Low <: High, F[_]]
        |enum EnumOwner[A >: Low <: High, F[_]]:
        |  case One[A >: Low <: High, F[_]](value: F[A]) extends EnumOwner[A, F]
        |
        |def functionOwner[A >: Low <: High, F[_]]: A = ???
        |type AliasOwner[A >: Low <: High, F[_]] = F[A]
        |opaque type OpaqueOwner >: Low <: High = High
        |
        |trait AbstractAliasOwner:
        |  type Abstract >: Low <: High
        |
        |trait Outer[A >: Low <: High, F[_]]:
        |  class NestedClass[B >: Low <: High, G[_]]
        |  trait NestedTrait[C >: Low <: High, H[_]]
        |  def nestedFunction[D >: Low <: High, I[_]]: D = ???
        |  type NestedAlias[E >: Low <: High, J[_]] = J[E]
        |  type NestedAbstract >: Low <: High
        |""".stripMargin
    val file   = physical("BoundsOwners1.scala", source)

    val parameters = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).asScala.toVector
    Vector("A", "B", "C", "D", "E").foreach: name =>
      assertTrue(
        s"missing bounded parameter $name in ${parameters.map(_.getText)}",
        parameters.exists: parameter =>
          parameter.name == name &&
            parameter.lowerTypeElement.map(_.getText) == Some("Low") &&
            parameter.upperTypeElement.map(_.getText) == Some("High")
      )
    Vector("F", "G", "H", "I", "J").foreach: name =>
      val parameter = parameters
        .find(_.name == name)
        .getOrElse(
          throw new AssertionError(s"missing higher-kinded parameter $name in ${parameters.map(_.getText)}")
        )
      assertEquals(Vector("_"), parameter.typeParameters.map(_.getText).toVector)
      assertEquals(Some("[_]"), parameter.typeParametersClause.map(_.getText))
      val range     = parameter.typeParametersClause.get.getTextRange
      assertEquals("[_]", source.substring(range.getStartOffset, range.getEndOffset))
    assertTrue(parameters.exists(parameter => parameter.name == "A" && parameter.isContravariant))
    assertTrue(parameters.exists(parameter => parameter.name == "A" && parameter.isCovariant))

    val aliases = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAlias]).asScala.toVector
    Vector("Abstract", "NestedAbstract").foreach: name =>
      val alias = aliases.find(_.name == name).get
      assertEquals(s"lower bound for $name in ${alias.getText}", Some("Low"), alias.lowerTypeElement.map(_.getText))
      assertEquals(s"upper bound for $name in ${alias.getText}", Some("High"), alias.upperTypeElement.map(_.getText))
    val opaque  = aliases.find(_.name == "OpaqueOwner").get.asInstanceOf[ScTypeAliasDefinition]
    assertEquals(Some("Low"), opaque.lowerTypeElement.map(_.getText))
    assertEquals(Some("High"), opaque.upperTypeElement.map(_.getText))
    assertEquals(Some("High"), opaque.aliasedTypeElement.map(_.getText))

  def testTypeDefinitionTermLambdaOwnsNestedTypeAndTermParameterClauses(): Unit =
    val source =
      """import scala.language.experimental.modularity
        |
        |type Vec[T](n: Int)(size: Long) = Array[T]
        |""".stripMargin
    val file   = physical("TermLambda1.scala", source)

    val alias = PsiTreeUtil.findChildOfType(file, classOf[ScTypeAliasDefinition])
    assertEquals("type Vec[T](n: Int)(size: Long) = Array[T]", alias.getText)
    assertEquals(Vector("T"), alias.typeParameters.map(_.name).toVector)
    assertEquals(Some("Array[T]"), alias.aliasedTypeElement.map(_.getText))

    val clauses = PsiTreeUtil.findChildrenOfType(alias, classOf[ScParameters]).asScala.toVector
    assertEquals(Vector("(n: Int)", "(size: Long)"), clauses.map(_.getText))
    val clause  = PsiTreeUtil.findChildrenOfType(alias, classOf[ScParameterClause]).asScala.toVector
    assertEquals(Vector(Vector("n"), Vector("size")), clause.map(_.parameters.map(_.name).toVector))

  def testNestedSiblingAndMixedEmptyConstructorClausesKeepTheirSourceOwner(): Unit =
    val source  =
      """class Outer[A]( /* outer-first */ )():
        |  class Inner[B]( /* inner-first */ )()
        |class Mixed[C]( /* mixed-first */ )(value: C)( /* mixed-last */ )
        |class Sibling[D]( /* sibling-first */ )()
        |""".stripMargin
    val file    = physical("EmptyClauses1.scala", source)
    val classes = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScClass])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)

    assertEquals(Vector("Outer", "Inner", "Mixed", "Sibling"), classes.map(_.name))
    assertEquals(
      Vector(
        Vector("( /* outer-first */ )", "()"),
        Vector("( /* inner-first */ )", "()"),
        Vector("( /* mixed-first */ )", "(value: C)", "( /* mixed-last */ )"),
        Vector("( /* sibling-first */ )", "()")
      ),
      classes.map(_.constructor.get.parameterList.clauses.map(_.getText).toVector)
    )
    assertEquals(
      Vector(Vector.empty, Vector.empty, Vector("value"), Vector.empty),
      classes.map(_.constructor.get.parameters.map(_.name).toVector)
    )
    classes.foreach: owner =>
      owner.constructor.get.parameterList.clauses.foreach(clause => assertSame(owner.constructor.get, clause.owner))

  def testCopiesPointersEditsAndFailClosedRecoveryPreserveBounds(): Unit =
    val source          =
      """trait High
        |trait Low extends High
        |trait Other
        |trait Owner[+A >: Low <: High, F[_]]
        |type Wild = List[? >: Low <: High]
        |type Lambda = [X >: Low <: High] =>> List[X]
        |""".stripMargin
    val file            = physical("BoundsEdits1.scala", source)
    val owner           = PsiTreeUtil.findChildrenOfType(file, classOf[ScTrait]).asScala.find(_.name == "Owner").get
    val bounded         = owner.typeParameters.head
    val wildcard        = PsiTreeUtil.findChildOfType(file, classOf[ScWildcardTypeElement])
    val lambda          = PsiTreeUtil.findChildOfType(file, classOf[ScTypeLambdaTypeElement])
    val ownerPointer    = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(owner)
    val boundedPointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(bounded)
    val wildcardPointer = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(wildcard)
    val lambdaPointer   = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(lambda)
    val copy            = file.copy()
    assertEquals(source, copy.getText)
    assertEquals("? >: Low <: High", PsiTreeUtil.findChildOfType(copy, classOf[ScWildcardTypeElement]).getText)
    assertEquals(bounded.getText, bounded.copy().getText)
    assertEquals(wildcard.getText, wildcard.copy().getText)
    assertEquals(lambda.getText, lambda.copy().getText)

    val document     = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val wildcardHigh = document.getText
      .indexOf("type Wild") + document.getText.substring(document.getText.indexOf("type Wild")).indexOf("High")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(wildcardHigh, wildcardHigh + 4, "Other")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("Owner", ownerPointer.getElement.name)
    assertEquals(Some("High"), boundedPointer.getElement.upperTypeElement.map(_.getText))
    assertEquals(Some("Other"), wildcardPointer.getElement.upperTypeElement.map(_.getText))
    assertEquals("[X >: Low <: High] =>> List[X]", lambdaPointer.getElement.getText)

    val insertion    = document.getText.indexOf("List[X]") + "List[X]".length
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(insertion, " | Other")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    file.getChildren
    val editedLambda = PsiTreeUtil.findChildOfType(file, classOf[ScTypeLambdaTypeElement])
    assertEquals("[X >: Low <: High] =>> List[X] | Other", editedLambda.getText)
    val editedInfix  = PsiTreeUtil.findChildOfType(editedLambda, classOf[ScInfixTypeElement])
    assertEquals("List[X] | Other", editedInfix.getText)
    assertEquals("List[X]", editedInfix.left.getText)
    assertEquals("|", editedInfix.operation.getText)
    assertEquals(Some("Other"), editedInfix.rightOption.map(_.getText))

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.deleteString(insertion, insertion + " | Other".length)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    file.getChildren
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(file.getText))
        .isEmpty
    )
    assertEquals(
      "[X >: Low <: High] =>> List[X]",
      PsiTreeUtil.findChildOfType(file, classOf[ScTypeLambdaTypeElement]).getText
    )

  def testBoundsOwnersSerializeIndexAndReloadWithoutAst(): Unit =
    val source      =
      """package boundsstubs
        |trait High
        |trait Low extends High
        |trait Owner[+A >: Low <: High, F[_]]
        |type Wild = List[? >: Low <: High]
        |type Lambda = [X >: Low <: High] =>> List[X]
        |opaque type Alias >: Low <: High = High
        |""".stripMargin
    val file        = physical("BoundsStubs1.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val beforeShape = stubShape(stubs)
    val beforeIndex = indexShape(stubs)
    assertTrue(beforeShape.exists(_.endsWith("|scala.type parameter")))
    assertTrue(beforeShape.count(_.endsWith("|scala.type alias definition")) == 3)
    assertTrue(beforeIndex.contains("sc.type.alias.name|Wild"))
    assertTrue(beforeIndex.contains("sc.type.alias.name|Lambda"))
    assertTrue(beforeIndex.contains("sc.type.alias.name|Alias"))

    val output   = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(beforeShape, stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(beforeShape, stubShape(file.getStubTree.getPlainList.asScala))

  def testBoundsNavigationRenameAndUsagesRetainSourceNames(): Unit =
    val source    =
      """trait High
        |trait Low extends High
        |trait Owner[A >: Low <: High]
        |type Lambda = [X >: Low <: High] =>> List[X]
        |""".stripMargin
    val file      = physical("BoundsNavigation1.scala", source)
    val owner     = PsiTreeUtil.findChildrenOfType(file, classOf[ScTrait]).asScala.find(_.name == "Owner").get
    val parameter = owner.typeParameters.head
    val alias     = PsiTreeUtil.findChildOfType(file, classOf[ScTypeAliasDefinition])
    Vector(owner, parameter, alias).foreach(element => assertSame(element, element.getNavigationElement))
    assertTrue(myFixture.findUsages(owner).isEmpty)
    assertTrue(myFixture.findUsages(alias).isEmpty)
    assertTrue(myFixture.findUsages(parameter).isEmpty)
    myFixture.renameElement(parameter, "Renamed")
    myFixture.renameElement(alias, "RenamedLambda")
    myFixture.renameElement(owner, "RenamedOwner")
    assertEquals(
      """trait High
        |trait Low extends High
        |trait RenamedOwner[Renamed >: Low <: High]
        |type RenamedLambda = [X >: Low <: High] =>> List[X]
        |""".stripMargin,
      file.getText
    )

  def testLaterTypeFamiliesRemainFailClosed(): Unit =
    Vector(
      "type Rejected = Int match { case Int => String }\n",
      "type Rejected = { type A = Int }\n",
      "type Rejected = Int @unchecked\n",
      "type Rejected = '[Int]\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/BoundsRejected${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      file.getChildren
      assertTrue(
        source,
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
          .nonEmpty
      )
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDefinition]).isEmpty)

  private def stubShape(stubs: Iterable[Stub]): Vector[String] = stubs.iterator
    .flatMap(stub =>
      Option(stub.getStubSerializer).map(serializer => s"${stub.getClass.getName}|${serializer.getExternalId}")
    )
    .toVector

  private def indexShape(stubs: Iterable[Stub]): Vector[String] =
    val result = Vector.newBuilder[String]
    val sink   = new IndexSink:
      override def occurrence[Psi <: PsiElement, K](indexKey: StubIndexKey[K, Psi], value: K): Unit =
        result += s"${indexKey.toString}|${value.toString}"
    stubs.foreach(stub =>
      Option(stub.getStubSerializer).foreach(
        _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
      )
    )
    result.result()

  private def physical(name: String, source: String) =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    file

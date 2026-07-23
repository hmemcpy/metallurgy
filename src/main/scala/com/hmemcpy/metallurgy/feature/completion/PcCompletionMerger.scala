package com.hmemcpy.metallurgy.feature.completion

import com.hmemcpy.metallurgy.pc.PcCompletion
import com.intellij.codeInsight.completion.{CompletionParameters, CompletionResult, CompletionResultSet, PrefixMatcher}
import com.intellij.codeInsight.lookup.{
  LookupElement,
  LookupElementBuilder,
  LookupElementDecorator,
  LookupElementPresentation,
  LookupElementRenderer
}
import com.intellij.psi.PsiElement

private[completion] object PcCompletionMerger:

  def mergeRemainingContributors(
      parameters: CompletionParameters,
      result: CompletionResultSet,
      compilerItems: Seq[PcCompletion],
      symbolTarget: PcCompletion => Option[PsiElement] = _ => None
  ): Unit =
    val scalaResult   = result.withPrefixMatcher(ScalaBacktickPrefixMatcher(result.getPrefixMatcher))
    var nativeResults = Vector.empty[CompletionResult]
    scalaResult.runRemainingContributors(
      parameters,
      (nativeResult: CompletionResult) => nativeResults = nativeResults :+ nativeResult
    )

    mergeResults(compilerItems, nativeResults).foreach:
      case Left(compilerItem)  => scalaResult.addElement(compilerLookupElement(compilerItem, symbolTarget(compilerItem)))
      case Right(nativeResult) => scalaResult.passResult(nativeResult)

  def mergeResults(
      compilerItems: Seq[PcCompletion],
      nativeResults: Seq[CompletionResult]
  ): Seq[Either[PcCompletion, CompletionResult]] =
    merge(compilerItems, nativeResults)(
      result => result.getLookupElement.getLookupString,
      (result, compilerItem) => result.withLookupElement(decorate(result.getLookupElement, compilerItem))
    )

  def mergeLookupElements(
      compilerItems: Seq[PcCompletion],
      nativeItems: Seq[LookupElement],
      symbolTarget: PcCompletion => Option[PsiElement] = _ => None
  ): Seq[LookupElement] =
    merge(compilerItems, nativeItems)(
      _.getLookupString,
      (nativeItem, compilerItem) => decorate(nativeItem, compilerItem)
    ).map:
      case Left(compilerItem) => compilerLookupElement(compilerItem, symbolTarget(compilerItem))
      case Right(nativeItem)  => nativeItem

  def compilerLookupElement(item: PcCompletion, symbolTarget: Option[PsiElement] = None): LookupElement =
    val builder = symbolTarget
      .fold(LookupElementBuilder.create(item.lookupName))(
        LookupElementBuilder.create(_, item.lookupName)
      )
    decorate(
      builder
        .withPresentableText(item.lookupName),
      item
    )

  private def merge[A](
      compilerItems: Seq[PcCompletion],
      nativeItems: Seq[A]
  )(
      lookupName: A => String,
      overrideNative: (A, PcCompletion) => A
  ): Seq[Either[PcCompletion, A]] =
    val indexedCompilerItems = compilerItems.zipWithIndex
    val initialQueues        = indexedCompilerItems.groupMap(_._1.lookupName)(identity).view.mapValues(_.toList).toMap

    val (remainingQueues, mergedNativeItems) =
      nativeItems.foldLeft((initialQueues, Vector.empty[Either[PcCompletion, A]])):
        case ((queues, merged), nativeItem) =>
          queues.getOrElse(lookupName(nativeItem), Nil) match
            case (compilerItem, _) :: remaining =>
              val updatedQueues = queues.updated(lookupName(nativeItem), remaining)
              (updatedQueues, merged :+ Right(overrideNative(nativeItem, compilerItem)))
            case Nil                            =>
              (queues, merged :+ Right(nativeItem))

    val unmatchedCompilerItems =
      remainingQueues.valuesIterator.flatten.toSeq.sortBy(_._2).map((item, _) => Left(item))

    mergedNativeItems ++ unmatchedCompilerItems

  private def decorate(nativeItem: LookupElement, compilerItem: PcCompletion): LookupElement =
    new LookupElementDecorator[LookupElement](nativeItem):
      override def renderElement(presentation: LookupElementPresentation): Unit =
        super.renderElement(presentation)
        PcCompletionPresentation.render(compilerItem, presentation)

      override def getExpensiveRenderer: LookupElementRenderer[? <: LookupElement] =
        val renderer = getDelegate.getExpensiveRenderer.asInstanceOf[LookupElementRenderer[LookupElement]]
        if renderer == null then null
        else
          new LookupElementRenderer[LookupElementDecorator[LookupElement]]:
            override def renderElement(
                element: LookupElementDecorator[LookupElement],
                presentation: LookupElementPresentation
            ): Unit =
              renderer.renderElement(element.getDelegate, presentation)
              PcCompletionPresentation.render(compilerItem, presentation)

private final class ScalaBacktickPrefixMatcher private (delegate: PrefixMatcher)
    extends PrefixMatcher(delegate.getPrefix):

  private val prefixWithoutBackticks  = stripBackticks(getPrefix)
  private val matcherWithoutBackticks = delegate.cloneWithPrefix(prefixWithoutBackticks)

  override def prefixMatches(name: String): Boolean =
    if getPrefix == "`" then name.startsWith("`")
    else matcherWithoutBackticks.prefixMatches(stripBackticks(name))

  override def isStartMatch(name: String): Boolean =
    if getPrefix == "`" then name.startsWith("`")
    else matcherWithoutBackticks.isStartMatch(stripBackticks(name))

  override def cloneWithPrefix(prefix: String): PrefixMatcher =
    ScalaBacktickPrefixMatcher(delegate.cloneWithPrefix(prefix))

  private def stripBackticks(value: String): String =
    Option(value)
      .filter(_.length > 1)
      .map(_.stripPrefix("`").stripSuffix("`"))
      .getOrElse(value)

private object ScalaBacktickPrefixMatcher:
  def apply(delegate: PrefixMatcher): ScalaBacktickPrefixMatcher = new ScalaBacktickPrefixMatcher(delegate)

private[completion] object PcCompletionPresentation:

  private val MethodDetail = "^(\\(.*\\))\\s*:\\s*(.+)$".r

  def render(item: PcCompletion, presentation: LookupElementPresentation): Unit =
    semanticDetail(item).foreach:
      case MethodDetail(parameters, resultType) =>
        presentation.setTailText(parameters, true)
        presentation.setTypeText(resultType)
      case resultType                           =>
        presentation.clearTail()
        presentation.setTypeText(resultType)

  private def semanticDetail(item: PcCompletion): Option[String] =
    item.detail
      .orElse(labelDetail(item))
      .map(_.trim)
      .map(stripLookupName(item.lookupName, _))
      .map(_.stripPrefix(":").trim)
      .filter(_.nonEmpty)

  private def labelDetail(item: PcCompletion): Option[String] =
    Option.when(item.label.startsWith(item.lookupName)):
      item.label.drop(item.lookupName.length)

  private def stripLookupName(lookupName: String, detail: String): String =
    if detail.startsWith(lookupName) then detail.drop(lookupName.length).trim
    else detail

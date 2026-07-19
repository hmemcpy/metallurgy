package com.hmemcpy.metallurgy.feature.completion

import com.intellij.codeInsight.lookup.{LookupElement, LookupElementBuilder}

object CompletionDedup {

  /** Deduplicate pc-provided lookup elements against the ones the bundled contributor already added. Match by (name,
    * kind) as the primary key; for overloaded methods, disambiguate by the detail string (which carries the signature).
    */
  def dedupAgainst(
      pcItems: Seq[LookupElementBuilder],
      existingItems: Iterable[LookupElement]
  ): Seq[LookupElementBuilder] = {
    val existingKeys: Set[ItemKey] = existingItems.map(ItemKey.from).toSet
    pcItems.filterNot(item => existingKeys.contains(ItemKey.from(item)))
  }

  final case class ItemKey(name: String, detail: String)

  object ItemKey {
    def from(element: LookupElement): ItemKey = {
      val name   = element.getObject.toString
      val detail = element.getLookupString
      ItemKey(name, detail)
    }

    def from(builder: LookupElementBuilder): ItemKey =
      ItemKey(builder.getLookupString, builder.getLookupString)
  }
}

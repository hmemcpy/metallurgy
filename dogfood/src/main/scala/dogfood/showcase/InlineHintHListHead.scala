package dogfood.showcase

val h: Int *: String *: EmptyTuple = (1, "two")
val head = h.head
// screenshot the editor — with the plugin, an inline `: Int` hint appears after `head`

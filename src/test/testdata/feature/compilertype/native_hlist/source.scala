val h: Int *: String *: Boolean *: EmptyTuple = (1, "two", true)

val head: Int = h.head
val tailHead: String = h.tail.head
val tailTailHead: Boolean = h.tail.tail.head

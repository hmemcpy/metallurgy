# Native tuple HList navigation

Scala 3.5.2 stdlib-only native tuple fixture. Metallurgy must expose the compiler's element types through successive
`head`/`tail` selections; without Metallurgy the slots are empty and the deepest selection is unresolved. Related:
SCL-22088.

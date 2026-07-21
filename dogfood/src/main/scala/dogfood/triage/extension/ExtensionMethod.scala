package dogfood.triage.extension

extension (s: String) def slug: String = s.trim.toLowerCase

val x = " A ".slug

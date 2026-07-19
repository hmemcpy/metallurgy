# Structural typesafe config refinement

This isolates the transparent-inline structural type at the heart of the dogfood project's macro. Metallurgy must
publish the produced `Config` refinement into the bundled plugin's compiler-type slot so `c.name` resolves as `String`.
The off half proves that slot remains empty without Metallurgy; the full macro remains covered by the dogfood project
(SCL-21591, SCL-21789).

# Strict IntelliJ version range with runtime reflection fallback

Metallurgy declares `<idea-version since-build="261" until-build="261.*"/>` and a `<depends>org.intellij.scala:<bundled-version-for-261></depends>`. We refuse to load outside that range rather than attempt best-effort compatibility.

At runtime, calls into the bundled Scala plugin's classes — `CompilerType`, `ScalaProjectSettings`, `ScExpression.getType()`, the `Topic` machinery — are wrapped in a thin adapter layer. The adapter tries the typed (compile-time-checked) public API first; if a class is missing or a method signature has changed, it falls back to reflection and degrades gracefully (log + no-op + let bundled plugin handle it).

The trade-off:

- Strict range means users on the next EAP get a clear "waiting for Metallurgy to catch up" notification rather than mystery crashes. We follow the same policy the bundled Scala plugin itself uses. The cost is a release coordination burden on every IntelliJ minor — we ship a Metallurgy release within 4 weeks of each IntelliJ stable.
- Reflection fallback means we keep working when the bundled plugin's internal-ish APIs change underneath us. The cost is uglier code (adapter indirection) and subtler bugs (silent degradation is harder to notice than a crash).

We picked this combination because it gives the strongest correctness signal at the plugin-loading boundary (clear incompatibility outside the range) while still tolerating the bundled plugin's mid-range API churn (most API changes are additive or method-signature tweaks, both of which reflection handles).

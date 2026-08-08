# IC-261 lifecycle result

The lifecycle ran on IC `261.26222.65`, Scala plugin `2026.1.20`, exact compiler `3.7.4`, and JBR 25. It completed in
67.628 seconds with one passing JUnit test.

The broad replacement source contains bounded and higher-kinded parameters, source `_`, bounded wildcards, type
lambdas, abstract and opaque bounds, ordinary and named context bounds, and an aggregate context bound containing a
type lambda.

Observed gates:

- sbt project import completed and indexing was observed
- background tasks settled before parser readiness
- active main and test modules reached `Ready`
- the broad replacement source had no Scala language highlights
- compiler subscription preceded the edit
- compilation start and finish correlated with document version 1 and the edited source
- the Metallurgy compiler backend quiesced
- all background tasks remained empty for three seconds
- the syntax capability report cleared after the supported edit
- MessagePool and internal error outputs were empty
- final `idea.log` was clean after IDE shutdown

The one resolved highlight was the existing IDE-only typo finding for `infixed`. It was not a Scala language finding.

Preserved artifacts are under `ideprobe-tests/target/ideprobe-artifacts`. Important SHA-256 values:

- `latest/stages.log`: `436c29a5259892427793daf8e7b90f12dbac9e28f458ee898e43715473b2fa25`
- `latest/compiler-event-quiescence.txt`: `f9f348567a3bbbaffde2481b2c900b1189bf94a0ec649fa43ba14503f0e207ca`
- `latest/idea.log`: `ad161f9850745e08f0366100676cac11f239c3c8db57f2a7ae23aa6cd488f1f1`
- `latest/highlights.txt`: `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b`
- `latest/ide-messages.txt`: `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b`
- `latest/internal-errors.txt`: `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b`

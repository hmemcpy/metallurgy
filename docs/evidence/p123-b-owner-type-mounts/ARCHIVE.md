# P123-B evidence archive

Owner type mount evidence uses Scala 3.7.4 from source commit
`5407eeeb505f41c4562053d8aa024410da53835e`. The compiler and parser records are reproducible with these commands from
this directory, using JBR 25 for sbt:

```text
scala-cli compile --server=false --scala 3.7.4 OwnerTypeMounts.scala
scala-cli repl --server=false --scala 3.7.4 < repl.in
scala-cli compile --server=false --scala 3.7.4 --scalac-option -Xprint:typer OwnerTypeMounts.scala
sbt -batch -no-colors "testOnly com.hmemcpy.metallurgy.pc.Scala3OwnerTypeMountParserInventoryTest"
```

- Archive identifier: `metallurgy-123b-evidence/final-20260807T2016Z`
- Inner manifest: `SHA256SUMS`
- Manifest SHA-256: `502b4314e29afb3951ad688e7332ab8d8ea656ce467ffb979bb53511b1c68f1a`
- Verified entries: 76
- Archive size: 8.1 MiB
- Lifecycle manifest SHA-256: `2c271eb741d8569d487dfdb680211d31f837acbd49467f4a607ffd200cf30b56`
- Lifecycle entries: 43
- Retention: preserve through the maintenance and rollback window and until a verified replacement is recorded

The archive retains the raw build, lane, lifecycle, stress, and copied-origin records. The tracked Scala and REPL
inputs remain the source for exact regeneration.

# Metallurgy dogfood project

A standalone Scala 3.5.2 sbt project containing **valid, compiling** Scala 3 samples drawn from
Metallurgy's test harness — constructs the bundled IntelliJ Scala plugin *should* highlight without
false red. Use it to eyeball, in a **clean IntelliJ with the bundled Scala plugin but NO Metallurgy**,
whether native Scala 3 support now handles these on its own.

Every file here compiles (`sbt compile` is green) — so any red squiggle you see is a *false positive*
worth Metallurgy's attention, not a real error. (Basic type mismatches are already handled natively, so
there are no deliberate-error "control" files.)

## How to inspect

1. Open this `dogfood/` folder in IntelliJ IDEA (**bundled Scala plugin only — do not install
   Metallurgy**). Import as an sbt project.
2. In **Settings → Languages & Frameworks → Scala → Compiler**, ensure **Compiler-based
   highlighting** is on and **Use compiler types** is checked (the baseline Metallurgy requires).
3. Open each sample under `src/main/scala/dogfood/`. After the compile server settles, check whether
   any valid code stays red.

## What's here (24 samples, all compiling)

- `triage/` — from `Scala3GapTriageTest` (simple stdlib; headless CBH triage reported `native-red=false`):
  given/using, overload/eta, extension, enum widening, structural/refinement, quote/splice macros,
  `derives Mirror` type members, export clauses, `compiletime.ops` match types, Caprese capture
  checking (`File^`).
- `library/` — from `LibraryCbhTriageTest` (circe; also `native-red=false`): `derives Codec.AsObject`,
  semiauto `deriveCodec`, derive + decode.
- `fixture/` — the golden fixtures Metallurgy was built around: `compiletime.ops` int literals, recursive
  ADT `derives`, inline-match Peano, match-type reduction, generic-tuple HList, structural `Selectable`
  config, transparent-inline "typesafe config", zio-direct `defer`, kyo `defer`, `Mirror` type-member
  consumption, the two-module betasty `Person` (collapsed to one file).

## Notes

- `jing-openapi` was dropped: its 0.0.5 artifact was compiled against Scala 3.7.4, so its TASTy is
  unreadable on the 3.5.2 instance. The structural-typing story is still covered by `StructuralTypesafeConfig`.
- `build.sbt` pins `scala3-library`/`scala3-compiler` to 3.5.2 via `dependencyOverrides` — kyo/zio
  otherwise pull scala3-library 3.7.x by conflict resolution and break the instance.
- Headless provenance so far (`Scala3GapTriageTest` + `LibraryCbhTriageTest`): every valid sample is
  clean at steady state under CBH. This project is the human-eyeball confirmation, including constructs
  the headless harness can't easily express.

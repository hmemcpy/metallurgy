# Metallurgy dogfood project

A standalone Scala 3.7.4 sbt project containing **valid, compiling** Scala 3 samples drawn from
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

## What's here (25 samples, all compiling)

- `triage/` — from `Scala3GapTriageTest` (simple stdlib; headless CBH triage reported `native-red=false`):
  given/using, overload/eta, extension, enum widening, structural/refinement, quote/splice macros,
  `derives Mirror` type members, export clauses, `compiletime.ops` match types, Caprese capture
  checking (`File^`).
- `library/` — from `LibraryCbhTriageTest` (circe; also `native-red=false`): `derives Codec.AsObject`,
  semiauto `deriveCodec`, derive + decode.
- `fixture/` — the golden fixtures Metallurgy was built around: `compiletime.ops` int literals, recursive
  ADT `derives`, inline-match Peano, jing OpenAPI (`inlineYaml`), match-type reduction, generic-tuple
  HList, structural `Selectable` config, transparent-inline "typesafe config", zio-direct `defer`,
  kyo `defer`, `Mirror` type-member consumption, the two-module betasty `Person` (collapsed to one file).

## Notes

- Scala 3.7.4 (any ≥ 3.5 is supported; the version is immaterial to the inspection). `-experimental` is
  on for the Caprese capture-checking and jing `inlineYaml` samples.
- Metallurgy itself is per-module version-agnostic: `MtagsFetcher`/`PresentationCompilerResolver` fetch
  the exact-version `pc` for each module's Scala version, so the plugin is not pinned to one.
- Headless provenance so far (`Scala3GapTriageTest` + `LibraryCbhTriageTest`): every valid sample is
  clean at steady state under CBH. This project is the human-eyeball confirmation, including constructs
  the headless harness can't easily express.

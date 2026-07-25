# Showcase screenshots

Each file demonstrates one capability. Capture each **twice** — with the plugin **off**
(Metallurgy disabled for the module) and **on** (enabled) — and hover / frame as noted.
Drop the PNGs into `docs/screenshots/` and they'll be embedded in the README.

## Setup

- Project: `metallurgy-dogfood` (Scala 3.7.4, `-experimental`).
- Metallurgy: opt the module in (Settings → Metallurgy) and keep compiler-based
  highlighting on. For the "off" shots, disable Metallurgy for the module and reopen the file.
- Frame the editor tightly around the relevant lines (crop out the rest).

## Shot list

| File | Do | Expected with the plugin |
|---|---|---|
| `CompiletimeOps.scala` | hover `result` | `(4 : Int)` |
| `TransparentInline.scala` | hover `transparentPort` | `(8080 : Int)` |
| `MatchType.scala` | hover `first` | `Int` |
| `OpaqueType.scala` | hover `opaquePort` | `Ports.Port` |
| `InlineHintPolymorphic.scala` | screenshot editor | inline `: Int` hint after `applied` |
| `InlineHintHListHead.scala` | screenshot editor | inline `: Int` hint after `head` |

If a hover doesn't show the plugin's type immediately, trigger completion once
(type a `.` after the expression, then backspace) to fill the type, then hover.

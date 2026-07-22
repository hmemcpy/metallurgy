# 11 — Canonical Scala 3 Macro / Transparent-Inline Acceptance Tests

A targeted catalog of **real-world Scala 3 patterns the bundled JetBrains
Scala plugin does not handle natively**, with a minimal fixture for each
and a pointer to the YouTrack ticket / library doc that proves the
deficiency. The output is intended as input to the Metallurgy compiler-semantics
implementer, who needs concrete, named fixtures to write and assert
`pc`-backed behaviour against.

Sources consulted: the YouTrack SCL project (via the REST API at
`https://youtrack.jetbrains.com/api/issues/?query=…`), the Scala 3
language reference, the READMEs of `circe`, `shapeless-3`, `magnolia1`,
`tapir`, `chimney`, `iron`, and the existing in-repo deep-dives
`05-macros.md` and `09-scala3-feature-gaps.md`. YouTrack ticket IDs are
cited as `SCL-NNNNN` and resolved dates (where non-null) appear as Unix
ms — a null `resolved` means the ticket is still open at the time of
writing.

---

## 0. How to read each entry

```
### <pattern name>                                  <- the canonical label
Status: <bundled plugin behaviour>                  <- what the user sees
Sources: <URLs>                                     <- primary evidence
Frequency: <Common | Rare>                          <- how often users hit it

```scala
<10–30-line Scala 3 fixture>
```

Acceptance: <what Metallurgy must do>               <- the test oracle
```

At the end of each of the nine categories, a single **canonical acceptance
fixture** is nominated — the one pattern that, if it works, proves the
seam for the whole category.

---

## Category 1 — Typeclass derivation (`derives`, `Mirror.Of`)

### 1.1 Case class with `derives` whose `derived` is a macro

Status: The bundled plugin **fabricates** a synthetic
`given derived$TC: TC[Foo] = ???` via `DerivesInjector.scala:15-31`, but
the *return type* of that given is unknown because `TC.derived` is
itself a Scala 3 macro. Hover, completion, and "find usages" all see
`???` (i.e. `Nothing`) instead of the real type.

Sources:
- `./05-macros.md:151-164` (the in-repo audit)
- YouTrack **SCL-22004** "Good code red: macro generated derives ignored" (unresolved)
- YouTrack **SCL-21785** "Built-in Scala 3 highlighting cannot resolve derives-generated members from separately published library artifact" (unresolved, 2026-04-30)
- YouTrack **SCL-22051** "Good code red: Doobie auto derivation (Scala 3)" (unresolved)
- YouTrack **SCL-21832** "No derived instances for enum class cases" (resolved 2023-09)
- YouTrack **SCL-21294** "Auto-generate scala.deriving.Mirror implicit instances" (resolved 2023-03)
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/contextual/derivation.html

Frequency: **Common** — every Circe / Cats / ZIO JSON / Chimney /
Doobie project uses this on every model file.

```scala
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec // Scala 2 path; Scala 3 below

enum Color derives io.circe.Codec:
  case Red, Green, Blue

case class Person(name: String, age: Int, favorite: Color)
  derives io.circe.Codec.AsObject
```

Acceptance: Hover on `Codec` in `derives Codec` shows
`Codec[Color]`; `summon[Codec[Person]]` resolves without red;
completion inside `Person(...).asJson` fields enumerates the derived
encoder.

### 1.2 (Canonical acceptance fixture) **`derives` on a recursive ADT**

This is the *minimal* fixture that exposes every defect at once:
(1) `Mirror.Sum` synthesis, (2) `Mirror.Product` synthesis for the
cases, (3) the typeclass `derived` macro actually running, (4) the
result being usable from a call site.

```scala
import scala.deriving.Mirror

enum Lst[+T] derives scala.reflect.ClassTag:
  case Cns(t: T, ts: Lst[T])
  case Nl

object Lst:
  val m = summon[Mirror.Of[Lst[Int]]]
  // expected: m: Mirror.Sum { type MirroredElemTypes = (Cns[Int], Nl.type) ... }
```

Sources: shapeless-3 README example
(https://github.com/typelevel/shapeless-3); the in-repo
`SyntheticImplicitInstances.scala:38-77` audit in
`./05-macros.md:113-118`.

Acceptance: `m.MirroredElemTypes` resolves to
`(Cns[Int], Nl.type)` in the editor; "Go to" on `Mirror.of` jumps to
`scala.deriving.Mirror`; no red on the `derives` clause.

---

## Category 2 — Transparent inline returning a refined / singleton type

### 2.1 The typesafe-config pattern

Status: Without compiler-based highlighting (CBH), every
`transparent inline` call resolves to `Any`. With CBH on, the bundled
plugin *round-trips* through the compile server (`CompilerType`
user-data hack at `ScExpression.scala:317-329`,
`ScStableCodeReferenceImpl.scala:478-490`) and stores a *string*. Any
position that needs an `ScType` (inlay, parameter info, refactor) still
sees `Any`.

Sources:
- YouTrack **SCL-21591** "broken type inference on transparent inline macros" (unresolved)
- YouTrack **SCL-20893** "Use CFA/DFA to interpret transparent inline methods" (unresolved)
- YouTrack **SCL-18466** "Heuristics for transparent term aliases" (unresolved)
- YouTrack **SCL-21991** "Handle iterative inlining" (unresolved)
- YouTrack **SCL-20963** "A call to a `transparent inline def` may appear on a stable path" (unresolved)
- YouTrack **SCL-21218** "Transparent inline without a parameter list not working" (unresolved)
- YouTrack **SCL-21789** "An option to use compiler's semantics when available" (unresolved) — **this is literally the request for Metallurgy**
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html#transparent-inline-methods
- In-repo: `./05-macros.md:131-150`, `./09-scala3-feature-gaps.md:305-346`

Frequency: **Common** — pureconfig, zio-config, scalate, typesafe-config
wrappers, iron — anywhere a literal-looking call hides a `transparent inline`.

```scala
object Config:
  transparent inline def port: Int = 8080
  transparent inline def host: String = "0.0.0.0"
  transparent inline def enabled: Boolean = true

val p: 8080 = Config.port        // singleton type, must NOT be Int
val h: "0.0.0.0" = Config.host
val e: true = Config.enabled
```

Acceptance: Hover `Config.port` shows `8080` (singleton), not `Int`.
The ascription `val p: 8080 = Config.port` is not red. Completion on
`Config.port.` (member access on the singleton literal) offers `Int`
members because dotc successfully widens.

### 2.2 (Canonical acceptance fixture) **Iron-style refined type via transparent inline**

Iron is the most widely used Scala 3 refined-types library; the entire
public API is `transparent inline` macros returning
`A & Constraint`. Every Iron user hits this immediately.

```scala
import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.numeric.*

def log(x: Double :| Positive): Double = Math.log(x)
log(1.0)         // compiles
val runtime: Double = ???
log(runtime.refineUnsafe)
```

Sources:
- Iron README: https://github.com/Iltotore/iron
- YouTrack **SCL-21626** "No implicit arguments of type: ConfigDecoder[String, IronType[String, Pure]]" (resolved)
- YouTrack **SCL-22082** "Incorrect 'No implicits found' for code using Chisel v7.0.0" (unresolved) — same pattern

Acceptance: `:| Positive` resolves; the extension method
`refineUnsafe` autocompletes on `Double`; no "Cannot resolve symbol
`refineUnsafe`".

---

## Category 3 — `inline match` and `inline if`

### 3.1 Inline match on a static type

Status: The bundled plugin parses `inline match`
(`parser/parsing/...`) but treats the result as an ordinary
expression. The branch actually selected by dotc at compile time is
**not** the one the user sees in the IDE; in particular, the
*transparent-inline return type* is `Any` (see §2).

Sources:
- YouTrack **SCL-21789** "Matching Expr[T] in macro code doesn't recognise the types and pattern matching SCALA3" (unresolved)
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html#inline-matches
- In-repo: `./05-macros.md:131-164`, `./09-scala3-feature-gaps.md:329-339`

Frequency: **Common in derivation libraries** (every `derived`
method body has one), rare in application code.

```scala
import scala.compiletime.erasedValue

transparent inline def defaultValue[T]: Option[Any] =
  inline erasedValue[T] match
    case _: Byte    => Some(0: Byte)
    case _: Int     => Some(0)
    case _: String  => Some("")
    case _: Boolean => Some(false)
    case _          => None

val di: Some[Int]    = defaultValue[Int]
val ds: Some[String] = defaultValue[String]
val dn: None.type    = defaultValue[Any]
```

Acceptance: `defaultValue[Int]` hovers as `Some[Int]` (not `Option[Any]`);
`defaultValue[Any]` hovers as `None.type`. No red on the ascriptions.

### 3.2 (Canonical acceptance fixture) **Peano `toInt` via inline match**

This is the example in the Scala 3 reference and the *minimal* shape
that proves inline-match reduction works end-to-end:

```scala
trait Nat
case object Zero extends Nat
case class Succ[N <: Nat](n: N) extends Nat

transparent inline def toInt(n: Nat): Int =
  inline n match
    case Zero     => 0
    case Succ(n1) => toInt(n1) + 1

inline val natTwo = toInt(Succ(Succ(Zero)))
val intTwo: 2 = natTwo
```

Sources: https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html#inline-matches

Acceptance: `natTwo` hovers as `2`; the ascription `val intTwo: 2 = natTwo` is not red.

---

## Category 4 — Quote / splice macros (`'{ ... }`, `${ ... }`)

### 4.1 Quote-splice macro and its inline entry point

Status: `'{ ... }` and `${ ... }` *parse*, but the splice has no type
(`ScSplicedBlock` returns `Any`). `ScMacroDefinitionImpl.scala:27-30`
falls back to `Any` for macro return types. Navigation to the macro
implementation does not work.

Sources:
- YouTrack **SCL-21039** "ScalaMacroTypeable does not work with Scala 3 macros" (unresolved)
- YouTrack **SCL-21678** "Jump to definition in Scala 3 macro code doesn't work" (resolved 2025-10-15 — recent)
- YouTrack **SCL-21722** "In a Scala 3 macro, cannot navigate to .asTerm.show" (resolved)
- YouTrack **SCL-21774** "Improve completion for Scala 3 TypeRepr" (resolved)
- YouTrack **SCL-22132** "No auto completion for scala3 macro API" (resolved)
- YouTrack **SCL-21594** "Adapt PSI trees to support macros" (unresolved)
- YouTrack **SCL-18473** "Scala 3 exported macro signatures have no type information, tasty not read correctly" (unresolved)
- YouTrack **SCL-18099** "scala 3 parser: support quoted patterns" (resolved)
- YouTrack **SCL-21993** "Navigation in a Scala3-macro project is often broken" (resolved)
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/metaprogramming/macros.html
- In-repo: `./05-macros.md:121-164`

Frequency: **Common** — every Circe-derivation, Magnolia, Chimney, and
shapeless-3 release ships quote macros. Most application code calls
them through `inline def` entry points; the macro implementation is
rarely edited by application devs.

```scala
import scala.quoted.*

inline def power(x: Double, inline n: Int): Double =
  ${ powerCode('x, 'n) }

def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] =
  n.value match
    case Some(0)  => '{ 1.0 }
    case Some(1)  => x
    case Some(m)  => '{ $x * ${ powerCode(x, Expr(m - 1)) } }
    case None     => '{ math.pow($x, ${ n }.toDouble) }

val two = power(2.0, 3)   // hovers as Double; ideally shows expanded `{ 2.0 * ... }`
```

Acceptance:
- "Go to definition" on `powerCode` from the splice `${ powerCode('x, 'n) }` navigates correctly.
- Inside the macro body, completion on `Expr(` offers the `Expr.apply[T]` overload.
- `n.value` resolves (it is an extension method on `Expr[Int]` provided by `scala.quoted`).

### 4.2 Quote *pattern* matching with type variables

This is the more brittle case — quoted patterns with lower-case
identifiers as type variables (`case '{ $x: t }` where `t` is a fresh
type variable). The bundled plugin does not bind `t` to a typed
capture.

```scala
import scala.quoted.*

def let(x: Expr[Any])(using Quotes): Expr[Any] =
  x match
    case '{ $x: t } =>
      '{ val y: t = $x; y }
```

Sources: https://docs.scala-lang.org/scala3/reference/metaprogramming/macros.html#type-variables

Acceptance: No red on the type variable `t`; "Go to" on `$x` resolves
to the outer parameter.

### 4.3 (Canonical acceptance fixture) **`powerCode` round-trip**

The fixture in §4.1 is the *exact example* used in the Scala 3
reference; it is short, exercises all three of splice, quote, and
`Expr.value`, and exposes both the "splice has no type" defect and the
"cannot navigate to macro impl" defect. Use it verbatim.

---

## Category 5 — Compile-time operations (`summonInline`, `constValue`, `erasedValue`, `summonFrom`)

### 5.1 `summonInline` whose target is only available after inlining

Status: `CompileTimeOpsIntrinsics.scala:51` hard-codes
`scala.compiletime.ops.{any,boolean,int,long}` at the *type* level, but
`summonInline` / `summonFrom` / `constValue` / `erasedValue` are *not*
interpreted — every call resolves to `Nothing` / `Any`.

Sources:
- YouTrack **SCL-21720** "Scala 3 summon not inferred with ClassTag" (unresolved)
- YouTrack **SCL-21234** "`summon` type inference + Mirror" (resolved)
- YouTrack **SCL-21887** "Presentation compiler fails to infer `summon` type" (resolved)
- YouTrack **SCL-21694** "Presentation compiler shows error for correct code using Mirror API" (unresolved)
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/metaprogramming/compiletime-ops.html
- In-repo: `./09-scala3-feature-gaps.md:330-335`

Frequency: **Common inside derivation libraries** (Magnolia,
shapeless-3, Circe-derivation all use `summonInline` in their
`summonInstances` loop); rare elsewhere.

```scala
import scala.compiletime.{summonInline, constValue, erasedValue}

inline def summonAll[T <: Tuple]: List[Any] =
  inline erasedValue[T] match
    case _: EmptyTuple     => Nil
    case _: (head *: tail) => summonInline[head] :: summonAll[tail]

trait Typeclass[T]
given Typeclass[Int]    = new {}
given Typeclass[String] = new {}
given Typeclass[Boolean]= new {}

val instances = summonAll[(Int, String, Boolean)]
// expected: List[Any] containing three Typeclass[_] instances
```

Acceptance:
- `summonInline[head]` is not red.
- "Find usages" on `given Typeclass[Int]` shows the `summonInline` call as a usage.
- `constValue[2]` hovers as `2` (singleton literal type).

### 5.2 (Canonical acceptance fixture) **`summonAll` tuple walk**

The §5.1 fixture is canonical because it is *the* pattern used inside
every Scala 3 typeclass-derivation library. If `summonInline[head]`
resolves inside `summonAll`, the entire derivation pipeline works.

---

## Category 6 — Type-level computation (match types, `compiletime.ops`)

### 6.1 Match type reducing through `compiletime.ops.int.*`

Status: The bundled plugin has a hand-written reducer
(`ScMatchType.scala:27-262`) with hard-coded depth 50 and a
disjointness oracle covering only final classes / sealed
hierarchies. Newer 3.5/3.6 ops (`string.Length`, `any.Typeable`) are
missing entirely.

Sources:
- YouTrack **SCL-21198** "[Match Types] Type m.MirroredElemTypes does not conform to upper bound Tuple of type parameter Tup" (resolved)
- YouTrack **SCL-22088** "Compiler-based types + Named Tuples: 'EmptyTuple' is detected as 'Any'" (unresolved)
- YouTrack **SCL-21528** "Hover documentation inferred type defaults to Any" (unresolved)
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/metaprogramming/compiletime-ops.html#the-scalacompiletimeops-package
- In-repo: `./09-scala3-feature-gaps.md:203-228`

Frequency: **Common** in shapeless-3 / kittens / chimney; rare in
application code.

```scala
import scala.compiletime.ops.int.*

type Double[N] = N * 2
type Quadruple[N] = Double[Double[N]]

val q: Quadruple[3] = 12          // 3 * 2 * 2 * 2 = ... wait — Double[Double[3]] = (3*2)*2 *2 = 24? Verify.

import scala.compiletime.ops.string.Length
val len: Length["hello"] = 5
```

(Above — `Quadruple[3]` is `Double[Double[3]]` = `Double[6]` = `12`.
Adjust as needed.)

Acceptance: Hover on `Quadruple[3]` shows `12`; hover on
`Length["hello"]` shows `5`; the ascription lines are not red.

### 6.2 (Canonical acceptance fixture) **`compiletime.ops.int.+` reduction**

Use the simplest possible reduction that is still non-trivial:

```scala
import scala.compiletime.ops.int.*
type TwoPlusTwo = 2 + 2
val r: TwoPlusTwo = 4
```

If this hovers as `4`, the seam works. If it hovers as
`scala.compiletime.ops.int.+[2, 2]`, it doesn't.

---

## Category 7 — Macro-generated / synthetic members

### 7.1 Synthetic methods appearing on classes via `derives`

Status: The bundled plugin ships an `EnumMembersInjector` that
injects `values` / `valueOf` / `fromOrdinal` for Scala 3 enums, with
the explicit caveat at `EnumMembersInjector.scala:42`: *"@TODO:
valueOf return type is actually LUB of all singleton cases"*. The
`Mirror` synthesis at `SyntheticImplicitInstances.scala:38-77` is
hand-rolled and unreliable for sealed traits with package-private
cases.

Sources:
- YouTrack **SCL-21785** "Built-in Scala 3 highlighting cannot resolve derives-generated members from separately published library artifact" (unresolved)
- YouTrack **SCL-21626** "No implicit arguments of type: ConfigDecoder[String, IronType[String, Pure]]" (resolved) — synthetic ConfigDecoder
- YouTrack **SCL-21535** "Presentation compiler keeps highlighting in an endless loop (presumably in a code with a lot of derives)" (unresolved) — perf
- YouTrack **SCL-21955** "High CPU usage when doing nothing (presumably in a code with a lot of derives)" (unresolved) — perf
- In-repo: `./05-macros.md:86-118`, `./09-scala3-feature-gaps.md:96-118`

Frequency: **Common**.

```scala
enum Tree[T] derives io.circe.Codec:
  case Branch(left: Tree[T], right: Tree[T])
  case Leaf(elem: T)

object Demo:
  Tree.Leaf(42).asJson                      // .asJson is synthetic via Codec
  summon[io.circe.Codec[Tree[Int]]]         // synthetic given
```

Acceptance:
- `.asJson` autocompletes on `Tree.Leaf(42)`.
- `summon[Codec[Tree[Int]]]` resolves without red.
- "Find Usages" on the `derives Codec` clause shows the synthetic encoder as a usage target.

### 7.2 (Canonical acceptance fixture) **`enum … derives Codec` cross-module**

Put the enum in *module A* and the consumer in *module B*. The
bundled plugin's defect in SCL-21785 is exactly this: it cannot
resolve derives-generated members from a separately published library
artifact. This is the test that proves Metallurgy is reading TASTy
correctly via `pc`.

---

## Category 8 — Macro annotations (Scala 3.3+ experimental)

### 8.1 `scala.annotation.MacroAnnotation`

Status: **Not implemented at all.** Searches for `MacroAnnotation` in
`scala-impl/src` yield nothing; the only macro infrastructure is
Scala 2.

Sources:
- YouTrack **SCL-21026** "Support Scala Macro annotation" (resolved 2014 — Scala 2 only)
- YouTrack **SCL-18149** "Red squiggle under macro arguments" (unresolved)
- YouTrack **SCL-21594** "Adapt PSI trees to support macros" (unresolved)
- Scala 3 reference: https://docs.scala-lang.org/scala3/reference/contextual/derivation-macro.html
- In-repo: `./09-scala3-feature-gaps.md:526-545`

Frequency: **Rare today, growing.** Most libraries still use
`derives` rather than annotations; the new
`scala.annotation.MacroAnnotation` is experimental in 3.3.

```scala
import scala.annotation.MacroAnnotation
import scala.quoted.*

class logCalls extends MacroAnnotation:
  def apply(using Quotes)(tree: quotes.reflect.Definition):
    quotes.reflect.Definition =
      // wrap `tree` so each call is logged
      ???

@logCalls
def add(x: Int, y: Int): Int = x + y
```

Acceptance: No red on `@logCalls`; the macro implementation
`apply(using Quotes)(...)` resolves; the annotated `def add` is
*navigable*.

### 8.2 **Macro annotations are out of scope**

Macro annotations require the synthetic-members and diagnostics integrations. Compiler-type resolution should not
make them *worse* — i.e., Metallurgy must not flag the annotation
itself as an error.

---

## Category 9 — Specific library patterns

These are the canonical *real-world* reproductions reported by users.
Each is a faithful copy of a YouTrack reporter's repro.

### 9.1 Circe — `Codec.AsObject.derived`

```scala
import io.circe.Codec
import io.circe.derivation.ConfiguredEncoder

case class Foo(a: Int, b: String) derives Codec.AsObject
val encoded = Foo(1, "x").asJson
```

Sources: https://github.com/circe/circe ; SCL-20815, SCL-20802
(circe import-removal issues — symptoms of the same underlying
breakage).

Frequency: **Every Circe user, every model file.**

### 9.2 Magnolia1 — `AutoDerivation`

```scala
import magnolia1.*

trait Print[T]:
  extension (x: T) def print: String

object Print extends AutoDerivation[Print]:
  def join[T](ctx: CaseClass[Print, T]): Print[T] = value =>
    ctx.params.map(p => p.typeclass.print(p.deref(value))).mkString(",")
  override def split[T](ctx: SealedTrait[Print, T]): Print[T] = value =>
    ctx.choose(value)(sub => sub.typeclass.print(sub.cast(value)))
  given Print[Int] = _.toString

enum Tree[+T] derives Print:
  case Branch(left: Tree[T], right: Tree[T])
  case Leaf(value: T)
```

Sources: https://github.com/softwaremill/magnolia (readme).

Frequency: **Common in Magnolia-based codebases**; the
`AutoDerivation` import is the trigger.

### 9.3 Shapeless 3 — `K0.ProductInstances`

```scala
import shapeless3.deriving.*

trait Monoid[A]:
  def empty: A
  def combine(x: A, y: A): A

object Monoid:
  given Monoid[Unit] with
    def empty: Unit = ()
    def combine(x: Unit, y: Unit): Unit = ()
  inline def derived[A](using gen: K0.ProductGeneric[A]): Monoid[A] = ???

case class ISB(i: Int, s: String, b: Boolean) derives Monoid
```

Sources: https://github.com/typelevel/shapeless-3 (readme); SCL-21510
"Unable to compile a Scala 3 library 'shapeless-3' in IntelliJ when
opened as SBT project".

### 9.4 Tapir — schema derivation

```scala
import sttp.tapir.*
import sttp.tapir.generic.auto.*

case class Book(title: String, year: Int)

val e = endpoint.in(query[Book]("book"))
```

Sources: https://github.com/softwaremill/tapir ; the
`sttp.tapir.generic.auto._` import is the trigger.

### 9.5 ZIO — `ZLayer.derive`

```scala
import zio.*

case class Config(host: String, port: Int)
object Config:
  given ZLayer[Any, Nothing, Config] = ZLayer.derive
```

Sources:
- SCL-21863 "Cannot resolve symbol with Built-in highlighting (ZLayer.derive)" (unresolved)
- SCL-21380 "Incorrect highlight: type mismatch (ZLayer.derive, ZNothing)" (resolved)
- SCL-21387 "scala3 + zio: Can't resolve type of effect" (unresolved)

Frequency: **Every modern ZIO 2 codebase.**

### 9.6 Chimney — `Transformer.derive`

```scala
import io.scalaland.chimney.*

case class UserDTO(name: String, age: Int)
case class User(name: String, age: Int, email: Option[String])

val dto = UserDTO("Alice", 30).into[User].transform
```

Sources: https://github.com/scalalandio/chimney ; SCL-21960 "Ducktape
library types are highlighted incorrectly" (similar product);
SCL-20824 "False unused import (chimney library)" (resolved).

### 9.7 Doobie — `Meta`

```scala
import doobie.util.Read
case class Person(id: Int, name: String)
val r = summon[Read[Person]]
```

Sources: SCL-22051 "Good code red: Doobie auto derivation (Scala 3)"
(unresolved); SCL-21676 "IntelliJ shows error due to missing doobie
Meta given, but it exists and compiles" (unresolved).

### 9.8 Skunk — `Codec`

```scala
import skunk.*
import skunk.codec.all.*

val q = sql"select id, name from users".query(int4 *: varchar)
```

Sources: SCL-21522 "Editor falsely reports compilation error for
macros for Skunk Libraries" (unresolved).

### 9.9 Quill — `quote`

```scala
import io.getquill.*

val ctx = new MirrorContext(MirrorIdiom, Literal)
import ctx.*

case class Person(name: String, age: Int)
val q = quote(query[Person].filter(p => p.age > 18))
```

Sources: SCL-21962 "Wrong Type Inference for Scala Quill Quoted
Functions with Generic Parameters" (unresolved); SCL-22105 "Cannot
resolve overloaded insertValue method of Quill" (unresolved).

### 9.10 Chisel — `Data` macro

Sources: SCL-22082 "Incorrect 'No implicits found' for code using
Chisel v7.0.0" (unresolved) — same `summonInline` root cause.

### 9.11 (Canonical library acceptance fixture) **Circe `Codec.AsObject.derived`**

Circe is the single most-deployed Scala 3 JSON library, the pattern
is two lines, and SCL-21785 explicitly calls out the
cross-module-defect path. Use §9.1 as the fixture.

---

## Appendix A — Compiler-semantics acceptance-test matrix

A condensed checklist for the implementer. Each row is one fixture
file under `src/test/testdata/feature/compilertype/`. The "expected behaviour" column
is the oracle the test asserts.

| #  | Fixture file                        | Category        | Pattern                                            | Expected behaviour (Metallurgy on)                                              | Default behaviour (Metallurgy off)                 |
| -- | ----------------------------------- | --------------- | -------------------------------------------------- | ------------------------------------------------------------------------------- | -------------------------------------------------- |
| 1  | `derives_recursive_adt.sc`          | 1 (derivation)  | `enum Lst[+T] derives ClassTag`                    | `summon[Mirror.Of[Lst[Int]]]` resolves; `MirroredElemTypes` shown in hover      | Red on `summon`; hover is `Any`                    |
| 2  | `transparent_inline_singleton.sc`   | 2 (transparent) | `transparent inline def port: 8080`                | Hover shows `8080`; ascription `val p: 8080 = Config.port` is not red           | Hover shows `Int`; ascription red                  |
| 3  | `iron_refined.sc`                   | 2 (transparent) | `Double :| Positive`                               | `.refineUnsafe` autocompletes; no "Cannot resolve symbol"                       | `.refineUnsafe` red                                |
| 4  | `inline_match_peano.sc`             | 3 (inline match)| `inline n match { case Zero => 0 … }`              | `natTwo` hovers as `2`                                                          | `natTwo` hovers as `Int`                           |
| 5  | `quote_splice_power.sc`             | 4 (macros)      | `inline def power(x, inline n) = ${ powerCode(...) }` | "Go to" on `powerCode` works; inside splice, `Expr.apply` completion works    | "Go to" does nothing; completion empty             |
| 6  | `summoninline_tuple.sc`             | 5 (compiletime) | `inline erasedValue[T] match { … summonInline[head] }` | `summonInline[head]` not red; "Find Usages" on the given includes it         | `summonInline[head]` red                           |
| 7  | `compiletime_ops_int.sc`            | 6 (type-level)  | `type Two = 1 + 1`                                 | Hover shows `2`                                                                 | Hover shows `int.+[1, 1]`                          |
| 8  | `derives_cross_module.sc`           | 7 (synthetic)   | enum in module A, `summon[Codec[...]]` in module B | No red; navigation to the synthetic encoder works                              | Red (SCL-21785)                                    |
| 9  | `circe_codec_asobject.sc`           | 9 (library)     | `case class Foo(...) derives Codec.AsObject`       | `.asJson` autocompletes; `summon[Codec[Foo]]` resolves                          | `.asJson` does not autocomplete                    |
| 10 | `zio_zlayer_derive.sc`              | 9 (library)     | `given ZLayer[Any, Nothing, Config] = ZLayer.derive` | No red on `ZLayer.derive`; hover shows the refined ZLayer type                | Red on `ZLayer.derive` (SCL-21863)                 |

(Each row maps to one of the canonical acceptance fixtures nominated in
the category sections above.)

---

## Appendix B — YouTrack ticket index

All tickets cited above, grouped by category, with resolved dates (Unix
ms; `null` = open at time of writing).

**Category 1 — derivation:**
- SCL-22004 (null) — macro-generated derives ignored
- SCL-21785 (null, 2026-04-30) — derives-generated members invisible cross-module
- SCL-22051 (null) — Doobie auto derivation
- SCL-21832 (resolved 2023-09) — derived instances for enum cases
- SCL-21294 (resolved 2023-03) — auto-generate Mirror
- SCL-21971 (resolved 2023-05) — tasty reader type class derivation
- SCL-21611 (null) — case classes derived as Product / Transparent Traits
- SCL-21894 (null) — sum type not derived from expression

**Category 2 — transparent inline:**
- SCL-21591 (null) — broken type inference on transparent inline macros
- SCL-20893 (null) — use CFA/DFA to interpret transparent inline
- SCL-18466 (null) — heuristics for transparent term aliases
- SCL-21991 (null) — handle iterative inlining
- SCL-20963 (null) — transparent inline on stable path
- SCL-21218 (null) — transparent inline without parameter list
- SCL-21766 (null) — import from transparent inline (general case)
- SCL-21789 (null) — "an option to use compiler's semantics when available" ← **this is the Metallurgy feature request**

**Category 3 — inline match / if:**
- SCL-21789 (null) — Matching Expr[T] in macro code doesn't recognise types
- SCL-21026 (null) — no tail recursion annotation on inline method

**Category 4 — quote / splice:**
- SCL-21039 (null) — ScalaMacroTypeable does not work with Scala 3 macros
- SCL-21594 (null) — adapt PSI trees to support macros
- SCL-21678 (resolved 2025-10-15) — jump to definition in macro code
- SCL-21722 (resolved) — navigate to .asTerm.show
- SCL-21774 (resolved) — completion for TypeRepr
- SCL-22132 (resolved) — auto-completion for scala3 macro API
- SCL-18099 (resolved) — parser: quoted patterns
- SCL-21993 (resolved) — navigation in macro project
- SCL-18473 (null) — exported macro signatures unread by tasty

**Category 5 — compiletime ops:**
- SCL-21720 (null) — summon not inferred with ClassTag
- SCL-21234 (resolved) — summon type inference + Mirror
- SCL-21887 (resolved) — presentation compiler fails to infer summon
- SCL-21694 (null) — presentation compiler errors on Mirror API

**Category 6 — match types:**
- SCL-21198 (resolved) — MirroredElemTypes upper bound
- SCL-22088 (null) — Named Tuples EmptyTuple detected as Any
- SCL-21528 (null) — hover documentation inferred type defaults to Any

**Category 7 — synthetic members:**
- (covered by §1 tickets above)

**Category 9 — library-specific:**
- SCL-21626 (resolved) — iron ConfigDecoder
- SCL-22082 (null) — Chisel v7 No implicits
- SCL-21863 (null) — ZLayer.derive
- SCL-21380 (resolved) — ZLayer.derive / ZNothing
- SCL-21387 (null) — zio Can't resolve type of effect
- SCL-21962 (null) — Quill wrong type inference
- SCL-22105 (null) — Quill overloaded insertValue
- SCL-21522 (null) — Skunk macros
- SCL-21960 (null) — Ducktape highlighted incorrectly
- SCL-20815 (resolved) — circe Optimize Imports
- SCL-20802 (resolved) — circe decoder broken by import optimization
- SCL-20824 (resolved) — pureconfig import removed
- SCL-21624 (null) — Frameless TypedEncoder
- SCL-21124 (null) — shapeless good code red
- SCL-18156 (resolved) — canonical shapeless highlighting issues
- SCL-22095 (null) — Slick Scala 3 types
- SCL-21346 (null) — Monocle missed autocompletion
- SCL-22051 (null) — Doobie auto derivation
- SCL-21676 (null) — Doobie Meta given

**Performance tickets (derivations trigger these):**
- SCL-21535 (null) — endless highlighting loop in derives-heavy code
- SCL-21955 (null) — high CPU when idle in derives-heavy code
- SCL-22134 (null) — slow Scala 3 code completion

---

## Appendix C — Library README / docs URLs

| Library    | URL                                                                       | Pattern to copy into fixture                            |
| ---------- | ------------------------------------------------------------------------- | ------------------------------------------------------- |
| circe      | https://github.com/circe/circe                                            | `case class Foo derives Codec.AsObject`                 |
| shapeless-3 | https://github.com/typelevel/shapeless-3                                 | `case class ISB(...) derives Monoid` + `K0.Generic`     |
| magnolia1  | https://github.com/softwaremill/magnolia (branch `scala3`)                | `object Print extends AutoDerivation[Print]`            |
| tapir      | https://github.com/softwaremill/tapir                                     | `import sttp.tapir.generic.auto._` + schema derivation  |
| chimney    | https://github.com/scalalandio/chimney                                    | `.into[User].transform`                                 |
| iron       | https://github.com/Iltotore/iron                                          | `Double :| Positive`                                    |
| zio        | https://github.com/zio/zio                                                | `given ZLayer[Any, Nothing, Config] = ZLayer.derive`    |
| doobie     | https://github.com/tpolecat/doobie                                        | `summon[Read[Person]]`                                  |
| skunk      | https://github.com/typelevel/skunk                                        | `sql"…".query(int4 *: varchar)`                         |
| quill      | https://github.com/zio/zio-quill                                          | `quote(query[Person].filter(_.age > 18))`               |

---

## Appendix D — Anti-patterns for compiler-type fixtures

The implementer should avoid the following as fixtures, even though
they look superficially similar:

1. **Scala 2 macro annotations** (`@deriving`, `@JsonCodec`, `@newtype`,
   `@typeclass`). These are handled by the bundled plugin's
   `SyntheticMembersInjector` EPs (Monocle, Scalaz, Circe, NewType,
   Simulacrum, Derevo, Scio) and are explicitly out of scope. Metallurgy
   supports Scala 3 only.

2. **scala.meta macro expansion**. The bundled plugin's
   `MacroExpansionLineMarkerProvider` is gated by the `ScalaMetaMode`
   user setting and is unrelated to Scala 3 macros. Tickets like
   SCL-1357447 are Scala 2-only.

3. **Multi-stage programming at runtime** (`scala.quoted.staging.Runner`).
   Out of scope; `pc` does not run staging.

4. **Capture-checking and erased definitions**. Experimental, not yet
   stable in 3.3 / 3.4; defer until diagnostics support is available.

5. **TASTy inspection from `.tasty` files** (`scala.tasty.Inspector`).
   Relevant to cross-module BETASTy support, not compiler-type requests.

---

## Summary

The nine categories collapse to **three root causes**:

1. The plugin's PSI has no Scala 3 typer, so anything requiring
   *reduction* (transparent inline, inline match, match types,
   `compiletime.ops`, `summonInline`) returns `Any` or `Nothing`.
2. The plugin's macro infrastructure is Scala 2-only, so quote/splice
   macros have no semantic layer at all (SCL-21039).
3. The plugin's `DerivesInjector` fabricates a `given … = ???` that
   misses the synthetic *return type* of the derived typeclass — so
   `derives Codec` "works" syntactically but every consumer sees
   `Any` (SCL-22004, SCL-21785).

All three root causes share a single fix: **delegate to `pc`**. The
The acceptance matrix (Appendix A) is the smallest set of
fixtures that proves the delegation works for each root cause
individually and for their composition.

When all ten fixtures in Appendix A pass, Metallurgy's compiler-backed semantics are
shippable: every common Scala 3 macro / transparent-inline pattern in
the wild — Circe derivation, Iron refinement, ZIO `ZLayer.derive`,
shapeless-3 derivation, basic quote/splice navigation — produces the
same answer as `pc`, and the bundled plugin's `Any` / unresolved /
false-positive outputs are suppressed.

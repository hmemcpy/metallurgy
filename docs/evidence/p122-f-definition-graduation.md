# P122-F definition graduation evidence

## Starting point

- Remote `idea261.x` commit: `bf7c9e64eab9a60bea3b43de02e83c4b51d17c90`
- Starting tree: `32e305cf6e0bb9b8c13ae639756db5ddea3b436a`
- Dedicated branch: `epic85/122f-definition-graduation`
- Stub schema: `7`

Both `git ls-remote` and the GitHub Git Data API returned the starting commit. The Git Data API returned the starting
tree. The P122-D and P122-E worktrees were clean, and their detached sbt servers were stopped before this worktree was
created.

## A-E object verification

Each commit object was read locally after its matching remote branch was verified with `git ls-remote`. The diff
digest is SHA-256 over `git diff --binary <parent> <commit>`.

| Packet | Commit | Tree | Diff SHA-256 |
| --- | --- | --- | --- |
| A | `378d9501499573cb571a1659a2d48ce7667fe551` | `49ebdfa36a0b72ad1d2471c9fe598d253e51e870` | `1952cb6155bb0b8340f973758a03dc28a80ffdc371aea0ede40e217237ddb74b` |
| B | `c1b98cae7329548cf69894374b107ccc323380dc` | `d843f2c9f17fb0275578721f5c9db2dcf31e5670` | `ec2264470ffd069fc6fcc6c70a3c0a9b7bd7828d7c5ed90947d982e3f933420c` |
| B readiness | `56289122c6133d63a7353d42d773a8e841cc30ca` | `0ac26bf1b3b8590e3534143e8df800ea02d9205d` | `3f7a588073025777485aa414e67a0c2488376138e28de0170ba07e099a465a23` |
| C | `55760530f7dbaa66ef791c9fba5b6b6905d43b07` | `77d5b5600fc912196cc39c3f505ae451315b10a4` | `8ae920b21e882e7d74d794291236fd77eac23dbdc3b01327df329132c6c744ef` |
| D | `995b065f601a0c7037e4821cd19c4af62ece8b17` | `438c74211e3a08de95b887be617ede6457b34cf3` | `81b5cf13f2d0b949860f80cf1cbb6d0abe4ce2befa08e3537cec1b7d520cdbaf` |
| E | `bf7c9e64eab9a60bea3b43de02e83c4b51d17c90` | `32e305cf6e0bb9b8c13ae639756db5ddea3b436a` | `206f8ba04b405bc88fdbc9042d5838ef728aa770f96fe14aec5e091d58eea54d` |

## Exact Scala 3.7.4 checks

All commands used JBR 25 and `--server=false`.

- Broad positive source SHA-256: `89239c24beb7ada01094af2e3347f157e5c6b367c74038f31e5ddaf58e3b58ec`
- Deferred-boundary source SHA-256: `d15461dd61d794cc0d46db899fa5f62d0cf408bca090f6c04f4bdb1f0697063e`
- Both sources compiled with `scala-cli compile --scala 3.7.4`.
- Positive `-Xprint:typer` output SHA-256: `2fe77080ec08f90ae082943a565213c51cf7f4c1476ed23ed20733b0df0eace0`
- REPL `:type` results were `List[Int]`, `Braced[Int, String, Boolean]`, and `Signal`; transcript SHA-256:
  `3fdf157b5e3ddaffe0f2cffb6f25db3249a89ba81378e2720138395d737a7cda`.

The broad positive source was:

```scala
class Marker extends scala.annotation.StaticAnnotation
@Marker final class Marked
@scala.annotation.nowarn trait Qualified

def topApply = List(1)
val topNumber = 1
var topIdent = topNumber
class Braced[A, +B, -C]()() {
  trait NestedTrait[T]()()
  object NestedObject:
    def selected = List(1).head
    val tupled = (topNumber, topIdent)
    var infixed = topNumber + topIdent
    type Abstract
  end NestedObject
  enum NestedEnum[+E]:
    case First
    case Second()
  end NestedEnum
}
trait Indented[-T]():
  def blocked = {
    val local = 1
    local
  }
end Indented
object Empty {}
enum Signal:
  case Ready
end Signal
```

The compiler-valid deferred-boundary source was:

```scala
class Parent
class TypedChild[A <: Any](value: Int = 1) extends Parent derives CanEqual:
  self: TypedChild[A] =>
  def returned(input: Int): Int = input
  val ascribed: Int = 1
  val expressionAscription = (1: Int)
  val (left, right) = (1, 2)
  type Alias = Int
  opaque type Hidden = Int
  given ordering: Ordering[Int] = Ordering.Int
  extension (value: Int)
    def doubled = value + value
class Secondary(value: Int):
  def this() = this(1)
```

## Focused integration checks

| Suite | Tests | Time | JUnit XML SHA-256 |
| --- | ---: | ---: | --- |
| `Scala3DefinitionParserInventoryTest` | 8 | 6.585s | `cd1ec0de9a1154a155866abaaef54bfa63c2bc5d560efeb54fc81247867de432` |
| `Scala3ParserVerticalSliceTest` | 11 | 69.050s | `62863a0f94dae790852e1e0745333db14214018cf34ddf6f69c33aee76b608b9` |
| `SourceEvidencePlannerTest` | 7 | 0.010s | `983e49b8efb6d67d11045ed609414668b4fa3b7b6bfa0b20cc2486ce4fdef07a` |
| `Scala3PsiProductionCatalogTest` | 65 | 2.348s | `aa21bacfb71867749cdd48b5e54a055a6b8f9f42a26bc8fa9aea1f6e3cdf1bfb` |
| `DotcPsiProducerEmitterTest` | 17 | 10.444s | `6fd1ae4559b762b5cacda203fd14c24bc06700277524256f71146cfadb3da7a0` |
| `Scala3ParentlessTemplatePsiTest` | 16 | 28.526s | final focused run before clean |
| `Scala3PackagePsiProducerTest` | 7 | 24.006s | `23557555ca3cacd8bda56702d3fa6fbfd86b8fd59f9a5d76e4ca38efc2cdd23a` |
| `Scala3ParserPreparationLifecycleTest` | 20 | 9.886s | `2e6f971e278a3b6c3c5981cc08b621ac9d8a872822a62bdc679bb978b3673e64` |
| `Scala3TypeInferenceFixtureContractTest` | 6 | 13.705s | `6ab98e8f96a131fb30cc1ede709d65bfd7c781067b1e98d391b9699a4eca2fb0` |
| `DialectProjectReopenTest` | 1 | 4.445s | `f252cd42d39d585288954b7eceed4a07a41b8d07a8c8ae015ff3dc4906620c32` |

The final baseline syntax PSI lane passed 34/34 tests. Summary SHA-256:
`5e4fbd823d8c290cc7d5f96d9cac4af91aa45ad9b278438479c4483e5c278d61`. Its manifest SHA-256 was
`51b41ea2b077e894973aa66c05d670fd88231070108ce5940b654ab7fe5118f7`, and its invocation manifest SHA-256 was
`4d81e123d9337016af92e828e31586397bad286ffaa6233eecb8ba044fc25222`.

The final local CI lane passed 62/62 tests. Summary SHA-256:
`f8d7cf2db9741603c71b391b9873403aec2664ff8a14792bc70bb55dbf11ace4`. Its manifest SHA-256 was
`c1b32894eb689c7df7eda5e2166191c77938c3aa4adaec12802139f7c2124733`, and its invocation manifest SHA-256 was
`de456232d7b926ec382bc1877ff52601c8a9e1d7ca4b27b03feaf4f14ae738ca`.

The unchanged package/import/export suite passed 7/7 in 23.933s. The parser preparation lifecycle passed 20/20,
the pinned type-inference fixture passed 6/6, and project reopen passed 1/1. The final combined parser inventory,
vertical slice, source evidence, catalog, and emitter run passed 108/108 in 88s.

## Demonstrated production correction

The first real IC-261 lifecycle run exposed `NoSuchElementException: None.get` from
`ScValueOrVariable.keywordToken` while IntelliJ collected sticky lines. Dotc's `Var` positioned product had placed
`kVAR` under the modifier-list output, and immutable `val` had no direct `kVAL` token. The correction capability-binds
the native `kVAL` surface, emits both property keywords directly under their native definition, and retains the dotc
`Var` product as semantic evidence. It adds no production or output role.

The correction changes the catalog fingerprint to
`1d8185f357c02f8bfb058ee111ec5339fa50ea83e9c13151cf19ebc47cff746a` because that fingerprint includes terminal
plans. Stub schema remains 7: no stub serializer, external ID, serialized field, index obligation, or output role
changed. Definition stub serialization and index round trips, AST unload/reload, and project reopen all passed. The
focused PSI test calls the installed plugin's direct-child `keywordToken` accessor for both `val` and `var` and gets
the exact keyword text.

## IC-261 lifecycle

The real IC-261 probe passed 11/11 in 70s after the correction. It observed import and indexing completion, Ready
modules, zero initial highlights, the expected fail-closed capability finding, capability recovery, a correlated
Scala compilation start and finish, backend quiescence, stable task drain, MessagePool size 0, IDE shutdown, and a
clean final `idea.log`.

| Artifact | SHA-256 |
| --- | --- |
| stages | `d0dec62d74dfbf5d704ebee4635a3d287528519131d101fa1290acca31d47439` |
| final idea.log | `a9ba6f2df8e9c862e9a8a3a44d14312a867d81d0c7b789d0b856fde656c94fd1` |
| internal errors | `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b` |
| IDE messages | `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b` |
| compiler event and quiescence | `467c9fb5794e7bccf3136fdc9ff1f8f4f3dc8e119c976f4626cc81ec71bd8d17` |
| final Metallurgy status | `bbdd572c98538997808365935a8695d0899852498bef8b63522186fcd2c2e061` |

## Final build and stress

- `scalafmtAll` and `scalafmtCheckAll` passed.
- Fresh `clean`, `Test/compile`, and `packageArtifact` passed in 25s. Final `metallurgy.jar` SHA-256:
  `199c766930c254ba56baca45b6aae481460d5fd85ba7c0824b9456eee545d7df`.
- The complete unchanged stress suite ran exactly once at the final gate and passed 6/6 in 831.598s within the 900s
  whole-suite bound. Its JUnit XML SHA-256 is
  `51be8a4f2c68940e81ceb8c9f62d84281216bf02ef180bf953b92ea68fc966f1`.
- `git diff --check` passed. Both lane manifests and invocation manifests remained byte-sorted.
- Medium review found the keyword correction narrow. Its cumulative-negative concern was resolved by executing the
  full deferred-boundary source as one fail-closed file. Its schema concern is answered by the unchanged serializer,
  external-ID, field, index, role, and schema inventories above.

## Protected copied-test boundary

- Tracked `Scala3CompatTestCase.scala` SHA-256: `93748c0c3c8a573e5c407e0e8c3a43c9b79f6f412b16c19c332dba7a6a178c11`
- Intentionally retained manifest SHA-256: `aac49d2d0059c258d64e4d3b8b1aad9f93548e5e7bbdadc7af7be642845abcfe`
- The copied-test sources, snippets, assertions, expected results, adapters, and pins are unchanged.
- `check`, copied-file verification, and origin verification stop only on this protected known mismatch. The origin
  check used `-Dintellij.scala.repo=~/git/intellij-scala`. `scalafmtCheckAll` passes independently. No protected byte
  or provenance pin was changed.

## Exclusions and readiness

No #123 type roles, #124 structured expression roles, #125 roles, or #128 work is present. Generic nested exact-range
compatibility expression islands remain intentional. P122-F is ready for issue-graduation review without advancing
`idea261.x` or closing #122.

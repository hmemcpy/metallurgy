#!/usr/bin/env bash
# Verify clean-workspace.sh against a synthetic repository layout.
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
clean_script="$script_dir/clean-workspace.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}
assert_exists() {
  [ -e "$1" ] || fail "expected to exist: $1"
}
assert_absent() {
  [ ! -e "$1" ] || fail "expected to be gone: $1"
}
force_rm() {
  chmod -R u+w "$1" 2>/dev/null || true
  rm -rf "$1"
}

[ -f "$clean_script" ] || fail "missing $clean_script"

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/clean-workspace-test.XXXXXX")"
trap 'force_rm "$fixture_root"' EXIT

make_fixture() {
  local root=$1
  mkdir -p "$root/scripts" || fail "cannot create $root/scripts"
  cp "$clean_script" "$root/scripts/clean-workspace.sh"
  mkdir -p "$root/target/idea-test-42" \
    "$root/target/scala-3.7.4" \
    "$root/target/maintenance-transfer/ro.git/objects" \
    "$root/target/test-evidence/baseline-syntax-psi/p125m-syntax" \
    "$root/target/test-evidence/baseline-syntax-psi/p125l-syntax" \
    "$root/target/test-evidence/ci/p125m-ci" \
    "$root/target/test-evidence/ci/p125l-ci" \
    "$root/target/test-evidence/p125m"
  : > "$root/target/idea-test-42/sandbox.bin"
  : > "$root/target/scala-3.7.4/classes.jar"
  : > "$root/target/maintenance-transfer/ro.git/objects/pack.pack"
  chmod -R a-w "$root/target/maintenance-transfer"
  : > "$root/target/test-evidence/baseline-syntax-psi/p125m-syntax/summary.json"
  : > "$root/target/test-evidence/baseline-syntax-psi/p125l-syntax/summary.json"
  : > "$root/target/test-evidence/ci/p125m-ci/summary.json"
  : > "$root/target/test-evidence/ci/p125l-ci/summary.json"
  : > "$root/target/test-evidence/p125m/note.txt"
  : > "$root/target/test-evidence/p125g-assignment.md"
}

run_clean() {
  local root=$1
  shift
  bash "$root/scripts/clean-workspace.sh" "$@"
}

# Dry run removes nothing and reports a plan.
root="$fixture_root/dry"
make_fixture "$root"
out="$(run_clean "$root" --keep p125m --dry-run)"
assert_exists "$root/target/idea-test-42/sandbox.bin"
assert_exists "$root/target/test-evidence/baseline-syntax-psi/p125m-syntax/summary.json"
assert_exists "$root/target/test-evidence/baseline-syntax-psi/p125l-syntax/summary.json"
assert_exists "$root/target/test-evidence/p125g-assignment.md"
grep -q "would delete" <<<"$out" || fail "dry run did not report a plan"
grep -q "kept" <<<"$out" || fail "dry run did not report keeps"
echo "ok: dry run changes nothing and reports the plan"

# --keep retains matching packet subtrees and removes everything else.
root="$fixture_root/keep"
make_fixture "$root"
out="$(run_clean "$root" --keep p125m)"
assert_exists "$root/target/test-evidence/baseline-syntax-psi/p125m-syntax/summary.json"
assert_exists "$root/target/test-evidence/ci/p125m-ci/summary.json"
assert_exists "$root/target/test-evidence/p125m/note.txt"
assert_absent "$root/target/test-evidence/baseline-syntax-psi/p125l-syntax"
assert_absent "$root/target/test-evidence/ci/p125l-ci"
assert_absent "$root/target/test-evidence/p125g-assignment.md"
assert_absent "$root/target/idea-test-42"
assert_absent "$root/target/scala-3.7.4"
assert_absent "$root/target/maintenance-transfer"
echo "ok: --keep p125m retains current-packet evidence and removes the rest"

# Without --keep every entry goes, including all evidence and read-only trees.
root="$fixture_root/all"
make_fixture "$root"
out="$(run_clean "$root")"
assert_absent "$root/target/test-evidence"
assert_absent "$root/target/idea-test-42"
assert_absent "$root/target/maintenance-transfer"
remaining="$(find "$root/target" -mindepth 1 | wc -l | tr -d ' ')"
[ "$remaining" -eq 0 ] || fail "target not empty after full cleanup: $remaining entries left"
echo "ok: full cleanup empties target including read-only trees"

# Evidence directory arguments that would escape target/ are refused.
root="$fixture_root/escape"
make_fixture "$root"
outside="$root/outside"
mkdir -p "$outside"
: > "$outside/precious.bin"
if run_clean "$root" --evidence-dir ../outside >/dev/null 2>&1; then
  fail "cleanup accepted an evidence directory outside target/"
fi
assert_exists "$outside/precious.bin"
if run_clean "$root" --evidence-dir legit/../../outside >/dev/null 2>&1; then
  fail "cleanup accepted a parent-escaping evidence directory"
fi
assert_exists "$outside/precious.bin"
assert_exists "$root/target/test-evidence/p125m/note.txt"
assert_exists "$root/target/idea-test-42/sandbox.bin"
echo "ok: escaping evidence directories are refused without touching their contents"

# A symlinked target directory is refused untouched.
root="$fixture_root/symlink"
make_fixture "$root"
victim="$fixture_root/symlink-victim"
mkdir -p "$victim/precious"
: > "$victim/precious/data.bin"
force_rm "$root/target"
ln -s "$victim" "$root/target"
if run_clean "$root" --keep p125m >/dev/null 2>&1; then
  fail "cleanup accepted a symlinked target directory"
fi
assert_exists "$victim/precious/data.bin"
echo "ok: symlinked target directory is refused without touching its contents"

echo "all clean-workspace tests passed"

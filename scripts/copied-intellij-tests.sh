#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 2
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

canonical_relative_path() {
  case "$1" in
    ''|/*|./*|*/./*|*/.|*'//'|../*|*/../*|*/..|.) return 1 ;;
    *) return 0 ;;
  esac
}

mode=${1:-}
case "$mode" in
  verify|generate|against-origin) ;;
  *) fail 'usage: scripts/copied-intellij-tests.sh <verify|generate|against-origin>' ;;
esac

require_command jq
require_command git
require_command cmp
require_command find
require_command grep
require_command sort

if [ -x /opt/homebrew/bin/gtimeout ]; then
  timeout_command=/opt/homebrew/bin/gtimeout
elif command -v gtimeout >/dev/null 2>&1; then
  timeout_command=$(command -v gtimeout)
elif command -v timeout >/dev/null 2>&1 && timeout --version 2>&1 | grep -q 'GNU coreutils'; then
  timeout_command=$(command -v timeout)
else
  fail 'GNU timeout is required (gtimeout on macOS, timeout on Linux)'
fi

actual_repo_root=$(git rev-parse --show-toplevel)
repo_root=${METALLURGY_COPIED_TEST_ROOT:-$actual_repo_root}
manifest="$repo_root/upstream-tests/intellij-scala.json"
selection="$repo_root/upstream-tests/intellij-scala-selection.json"
adapters="$repo_root/upstream-tests/adapters.json"
rewrite="$repo_root/upstream-tests/rewrites/named-type-arguments-inference.json"
[ -f "$manifest" ] || fail "manifest not found: $manifest"
[ -f "$selection" ] || fail "selection manifest not found: $selection"
[ -f "$adapters" ] || fail "adapter registry not found: $adapters"
[ -f "$rewrite" ] || fail "rewrite not found: $rewrite"

jq -e '
  keys == ["origin", "schemaVersion", "snapshotRoot", "suites", "supportFiles"]
  and .schemaVersion == 1
  and (.origin | keys == [
    "licenseBlob",
    "licensePath",
    "licenseSha256",
    "noticePath",
    "repository",
    "revision",
    "thirdPartyNoticePath",
    "thirdPartyNoticeSha256"
  ])
  and (.origin.repository == "https://github.com/JetBrains/intellij-scala.git")
  and (.origin.revision | test("^[0-9a-f]{40}$"))
  and (.origin.licenseBlob | test("^[0-9a-f]{40}$"))
  and (.origin.licenseSha256 | test("^[0-9a-f]{64}$"))
  and (.origin.thirdPartyNoticeSha256 | test("^[0-9a-f]{64}$"))
  and (.supportFiles | length == 3)
  and all(.supportFiles[];
    keys == ["originBlob", "originPath", "sha256"]
    and (.originBlob | test("^[0-9a-f]{40}$"))
    and (.sha256 | test("^[0-9a-f]{64}$"))
  )
  and (.suites | length == 1)
  and all(.suites[];
    keys == [
      "adapter",
      "capabilities",
      "classification",
      "generated",
      "id",
      "methods",
      "origin",
      "protectedClassBody",
      "rewrite"
    ]
    and .classification == "accepted-parity"
    and (.capabilities | length > 0)
    and (.methods | length > 0)
    and (.origin | keys == ["owner", "path", "sha256", "sourceBlob"])
    and (.generated | keys == ["owner", "path", "sha256"])
    and (.protectedClassBody | keys == [
      "generatedEndByte",
      "generatedStartByte",
      "originEndByte",
      "originStartByte",
      "sha256"
    ])
    and all(.methods[];
      keys == ["localName", "originName", "upstreamRuntimeName"]
      and (.originName | startswith("test"))
      and (.localName | startswith("test"))
    )
  )
' "$manifest" >/dev/null || fail 'invalid copied-test manifest schema'

jq -e '
  keys == ["invocations", "schemaVersion", "suite"]
  and .schemaVersion == 1
  and (.invocations | length > 0)
  and all(.invocations[];
    keys == ["classification", "originName"]
    and .classification == "accepted-parity"
    and (.originName | startswith("test"))
  )
' "$selection" >/dev/null || fail 'invalid selection manifest schema'

jq -e '
  keys == ["adapters", "schemaVersion"]
  and .schemaVersion == 1
  and (.adapters | length == 1)
  and all(.adapters[];
    keys == ["base", "helpers", "id"]
    and (.helpers | length > 0)
    and all(.helpers[];
      keys == [
        "localContractTest",
        "localContractTestPath",
        "localContractTestSha256",
        "localImplementationFiles",
        "originPaths",
        "symbol"
      ]
      and (.originPaths | length > 0)
      and (.localImplementationFiles | length > 0)
      and all(.localImplementationFiles[];
        keys == ["path", "sha256"]
        and (.sha256 | test("^[0-9a-f]{64}$"))
      )
      and (.localContractTestSha256 | test("^[0-9a-f]{64}$"))
    )
  )
' "$adapters" >/dev/null || fail 'invalid adapter registry schema'

jq -e '
  keys == ["adapterImport", "importAnchor", "owner", "package", "parser", "schemaVersion"]
  and .schemaVersion == 1
  and .parser == "copied-intellij-host-parser.v1"
  and (.package | keys == ["from", "to"])
  and (.owner | keys == ["baseFrom", "baseTo", "from", "to"])
' "$rewrite" >/dev/null || fail 'invalid rewrite schema'

while IFS= read -r declared_path; do
  canonical_relative_path "$declared_path" || fail "non-canonical path: $declared_path"
done < <(
  jq -r '
    .snapshotRoot,
    .origin.licensePath,
    .origin.thirdPartyNoticePath,
    .supportFiles[].originPath,
    .suites[].origin.path,
    .suites[].generated.path,
    .suites[].rewrite
  ' "$manifest"
)
while IFS= read -r declared_path; do
  canonical_relative_path "$declared_path" || fail "non-canonical adapter path: $declared_path"
done < <(
  jq -r '
    .adapters[].helpers[]
    | .localImplementationFiles[].path,
      .localContractTestPath
  ' "$adapters"
)

snapshot_root="$repo_root/$(jq -r '.snapshotRoot' "$manifest")"
license_path=$(jq -r '.origin.licensePath' "$manifest")
suite_origin_path=$(jq -r '.suites[0].origin.path' "$manifest")
suite_generated_path=$(jq -r '.suites[0].generated.path' "$manifest")
suite_origin="$snapshot_root/$suite_origin_path"
suite_generated="$repo_root/$suite_generated_path"

verify_hash() {
  local file_name=$1
  local expected=$2
  [ -f "$file_name" ] || fail "manifested file missing: $file_name"
  local actual
  actual=$(sha256_file "$file_name")
  [ "$actual" = "$expected" ] || fail "hash mismatch: $file_name"
}

verify_hash "$snapshot_root/$license_path" "$(jq -r '.origin.licenseSha256' "$manifest")"
verify_hash \
  "$repo_root/$(jq -r '.origin.thirdPartyNoticePath' "$manifest")" \
  "$(jq -r '.origin.thirdPartyNoticeSha256' "$manifest")"
while IFS=$'\t' read -r source_path expected_hash; do
  verify_hash "$snapshot_root/$source_path" "$expected_hash"
done < <(jq -r '.supportFiles[] | [.originPath, .sha256] | @tsv' "$manifest")
verify_hash "$suite_origin" "$(jq -r '.suites[0].origin.sha256' "$manifest")"
if [ "$mode" != generate ]; then
  verify_hash "$suite_generated" "$(jq -r '.suites[0].generated.sha256' "$manifest")"
fi

expected_snapshot_files="$repo_root/target/copied-intellij-tests/expected-snapshot-files.txt"
actual_snapshot_files="$repo_root/target/copied-intellij-tests/actual-snapshot-files.txt"
mkdir -p "$(dirname "$expected_snapshot_files")"
jq -r '
  .origin.licensePath,
  .supportFiles[].originPath,
  .suites[].origin.path
' "$manifest" | LC_ALL=C sort > "$expected_snapshot_files"
find "$snapshot_root" -type f | sed "s#^$snapshot_root/##" | LC_ALL=C sort > "$actual_snapshot_files"
cmp "$expected_snapshot_files" "$actual_snapshot_files" >/dev/null || fail 'snapshot contains missing or unmanifested files'

generated_root="$repo_root/src/test/generated/intellij-scala"
expected_generated_files="$repo_root/target/copied-intellij-tests/expected-generated-files.txt"
actual_generated_files="$repo_root/target/copied-intellij-tests/actual-generated-files.txt"
if [ "$mode" != generate ]; then
  printf '%s\n' "${suite_generated_path#src/test/generated/intellij-scala/}" > "$expected_generated_files"
  find "$generated_root" -type f | sed "s#^$generated_root/##" | LC_ALL=C sort > "$actual_generated_files"
  cmp "$expected_generated_files" "$actual_generated_files" >/dev/null ||
    fail 'generated root contains missing or unmanifested files'
fi

origin_package=$(jq -r '.package.from' "$rewrite")
generated_package=$(jq -r '.package.to' "$rewrite")
origin_owner=$(jq -r '.owner.from' "$rewrite")
generated_owner=$(jq -r '.owner.to' "$rewrite")
origin_base=$(jq -r '.owner.baseFrom' "$rewrite")
generated_base=$(jq -r '.owner.baseTo' "$rewrite")
import_anchor=$(jq -r '.importAnchor' "$rewrite")
adapter_import=$(jq -r '.adapterImport' "$rewrite")
revision=$(jq -r '.origin.revision' "$manifest")
generator="$actual_repo_root/scripts/CopiedIntellijSuiteGenerator.java"
java_command="${JAVA_HOME:?JAVA_HOME must point to JBR 25}/bin/java"
generated_output="$repo_root/target/copied-intellij-tests/generated/${suite_generated_path#src/test/generated/intellij-scala/}"

"$timeout_command" --kill-after=5s 30s "$java_command" "$generator" self-test
"$timeout_command" --kill-after=5s 30s "$java_command" "$generator" generate \
  "$suite_origin" \
  "$generated_output" \
  "$origin_package" \
  "$generated_package" \
  "$origin_owner" \
  "$generated_owner" \
  "$origin_base" \
  "$generated_base" \
  "$import_anchor" \
  "$adapter_import"

if [ "$mode" = generate ]; then
  printf 'Generated copied IntelliJ tests under %s\n' "$(dirname "$generated_output")"
  exit 0
fi

cmp "$generated_output" "$suite_generated" >/dev/null || fail 'checked-in generated suite is not reproducible'
body_record=$(
  "$timeout_command" --kill-after=5s 30s "$java_command" "$generator" verify-body \
    "$suite_origin" \
    "$suite_generated" \
    "$origin_package" \
    "$generated_package" \
    "$origin_owner" \
    "$generated_owner" \
    "$origin_base" \
    "$generated_base" \
    "$import_anchor" \
    "$adapter_import"
)
expected_body_record=$(jq -r '
  .suites[0].protectedClassBody
  | [
      .originStartByte,
      .originEndByte,
      .generatedStartByte,
      .generatedEndByte,
      .sha256
    ]
  | @tsv
' "$manifest")
[ "$body_record" = "$expected_body_record" ] || fail 'protected class-body coordinates or bytes differ'

manifest_methods="$repo_root/target/copied-intellij-tests/manifest-methods.txt"
selection_methods="$repo_root/target/copied-intellij-tests/selection-methods.txt"
jq -r '.suites[0].methods[].originName' "$manifest" | LC_ALL=C sort > "$manifest_methods"
jq -r '.invocations[].originName' "$selection" | LC_ALL=C sort > "$selection_methods"
cmp "$manifest_methods" "$selection_methods" >/dev/null || fail 'selection does not account for every manifested invocation'
[ "$(LC_ALL=C uniq -d "$manifest_methods" | wc -l | tr -d ' ')" -eq 0 ] || fail 'duplicate manifested invocation'

adapter_id=$(jq -r '.suites[0].adapter' "$manifest")
[ "$(jq --arg id "$adapter_id" '[.adapters[] | select(.id == $id)] | length' "$adapters")" -eq 1 ] ||
  fail "suite adapter does not resolve exactly once: $adapter_id"
while IFS= read -r helper_path; do
  jq -e --arg helper "$helper_path" 'any(.supportFiles[]; .originPath == $helper)' "$manifest" >/dev/null ||
    fail "adapter helper is not snapshotted: $helper_path"
done < <(jq -r --arg id "$adapter_id" '.adapters[] | select(.id == $id) | .helpers[].originPaths[]' "$adapters")
while IFS=$'\t' read -r local_path expected_hash; do
  verify_hash "$repo_root/$local_path" "$expected_hash"
done < <(
  jq -r --arg id "$adapter_id" '
    .adapters[]
    | select(.id == $id)
    | .helpers[]
    | (.localImplementationFiles[] | [.path, .sha256]),
      [.localContractTestPath, .localContractTestSha256]
    | @tsv
  ' "$adapters"
)

if [ "$mode" = against-origin ]; then
  origin_repo=${INTELLIJ_SCALA_REPOSITORY:-}
  [ -n "$origin_repo" ] || fail 'INTELLIJ_SCALA_REPOSITORY is required for against-origin verification'
  git -C "$origin_repo" cat-file -e "$revision^{commit}"
  if git -C "$origin_repo" cat-file -e "$revision:NOTICE" 2>/dev/null ||
    git -C "$origin_repo" cat-file -e "$revision:NOTICE.txt" 2>/dev/null; then
    fail 'pinned origin contains an unmanifested notice file'
  fi
  while IFS=$'\t' read -r source_path expected_blob; do
    actual_blob=$(git -C "$origin_repo" rev-parse "$revision:$source_path")
    [ "$actual_blob" = "$expected_blob" ] || fail "origin blob mismatch: $source_path"
    origin_copy="$repo_root/target/copied-intellij-tests/origin/$source_path"
    mkdir -p "$(dirname "$origin_copy")"
    git -C "$origin_repo" show "$revision:$source_path" > "$origin_copy"
    cmp "$origin_copy" "$snapshot_root/$source_path" >/dev/null || fail "snapshot differs from origin: $source_path"
  done < <(
    jq -r '
      [.origin.licensePath, .origin.licenseBlob],
      (.supportFiles[] | [.originPath, .originBlob]),
      (.suites[] | [.origin.path, .origin.sourceBlob])
      | @tsv
    ' "$manifest"
  )
fi

printf 'Copied IntelliJ test integrity verified (%s)\n' "$mode"

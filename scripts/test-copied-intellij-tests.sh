#!/usr/bin/env bash

set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/metallurgy-copied-tests.XXXXXX")

cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

copy_fixture() {
  local destination=$1
  mkdir -p \
    "$destination/src/test/generated" \
    "$destination/src/test/scala/com/hmemcpy/metallurgy/compat/scala3" \
    "$destination/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/adapters" \
    "$destination/target"
  cp -R "$repo_root/upstream-tests" "$destination/upstream-tests"
  cp -R "$repo_root/third_party" "$destination/third_party"
  cp -R "$repo_root/src/test/generated/intellij-scala" "$destination/src/test/generated/intellij-scala"
  cp \
    "$repo_root/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/adapters/Scala3TypeInferenceFixture.scala" \
    "$repo_root/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/adapters/Scala3TypeInferenceFixtureContractTest.scala" \
    "$destination/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/adapters/"
  cp \
    "$repo_root/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala" \
    "$repo_root/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/TypeInferenceTestInput.scala" \
    "$destination/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/"
  cp "$repo_root/THIRD_PARTY_NOTICES.md" "$destination/THIRD_PARTY_NOTICES.md"
}

expect_verification_failure() {
  local fixture_root=$1
  if METALLURGY_COPIED_TEST_ROOT="$fixture_root" "$repo_root/scripts/copied-intellij-tests.sh" verify; then
    printf 'mutation unexpectedly passed verification: %s\n' "$fixture_root" >&2
    exit 1
  fi
}

expect_verification_failure_with_message() {
  local fixture_root=$1
  local expected_message=$2
  local output
  if output=$(
    METALLURGY_COPIED_TEST_ROOT="$fixture_root" \
      "$repo_root/scripts/copied-intellij-tests.sh" verify 2>&1
  ); then
    printf 'mutation unexpectedly passed verification: %s\n' "$fixture_root" >&2
    exit 1
  fi
  printf '%s\n' "$output" | grep -F "$expected_message" >/dev/null || {
    printf 'mutation failed for the wrong reason: %s\n%s\n' "$fixture_root" "$output" >&2
    exit 1
  }
}

"$repo_root/scripts/copied-intellij-tests.sh" verify

generated_mutation="$temporary_root/generated-mutation"
copy_fixture "$generated_mutation"
perl -pi -e 's/pair\[A = Int\]/pair[A = Long]/' \
  "$generated_mutation/src/test/generated/intellij-scala/com/hmemcpy/metallurgy/generated/intellijscala/typeInference/NamedTypeArgumentsInferenceTest.scala"
grep -F 'pair[A = Long]' "$generated_mutation/src/test/generated/intellij-scala/com/hmemcpy/metallurgy/generated/intellijscala/typeInference/NamedTypeArgumentsInferenceTest.scala" >/dev/null
expect_verification_failure "$generated_mutation"

expected_mutation="$temporary_root/expected-mutation"
copy_fixture "$expected_mutation"
perl -pi -e 's#//List\[Int\]#//List[String]#' \
  "$expected_mutation/src/test/generated/intellij-scala/com/hmemcpy/metallurgy/generated/intellijscala/typeInference/NamedTypeArgumentsInferenceTest.scala"
grep -F '//List[String]' "$expected_mutation/src/test/generated/intellij-scala/com/hmemcpy/metallurgy/generated/intellijscala/typeInference/NamedTypeArgumentsInferenceTest.scala" >/dev/null
expect_verification_failure "$expected_mutation"

assertion_mutation="$temporary_root/assertion-mutation"
copy_fixture "$assertion_mutation"
perl -pi -e 's/= doTest\(/= assertTypeInferenceResult(/' \
  "$assertion_mutation/src/test/generated/intellij-scala/com/hmemcpy/metallurgy/generated/intellijscala/typeInference/NamedTypeArgumentsInferenceTest.scala"
grep -F '= assertTypeInferenceResult(' "$assertion_mutation/src/test/generated/intellij-scala/com/hmemcpy/metallurgy/generated/intellijscala/typeInference/NamedTypeArgumentsInferenceTest.scala" >/dev/null
expect_verification_failure "$assertion_mutation"

helper_mutation="$temporary_root/helper-mutation"
copy_fixture "$helper_mutation"
printf '\n' >> \
  "$helper_mutation/third_party/intellij-scala/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/TypeInferenceDoTest.scala"
expect_verification_failure "$helper_mutation"

adapter_mutation="$temporary_root/adapter-mutation"
copy_fixture "$adapter_mutation"
printf '\n' >> \
  "$adapter_mutation/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/adapters/Scala3TypeInferenceFixture.scala"
expect_verification_failure "$adapter_mutation"

implementation_mutation="$temporary_root/implementation-mutation"
copy_fixture "$implementation_mutation"
printf '\n' >> \
  "$implementation_mutation/src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala"
expect_verification_failure "$implementation_mutation"

notice_mutation="$temporary_root/notice-mutation"
copy_fixture "$notice_mutation"
printf '\n' >> "$notice_mutation/THIRD_PARTY_NOTICES.md"
expect_verification_failure "$notice_mutation"

for dot_path in \
  './THIRD_PARTY_NOTICES.md' \
  'alias/./THIRD_PARTY_NOTICES.md' \
  'alias/.'; do
  dot_path_mutation="$temporary_root/dot-path-$(printf '%s' "$dot_path" | tr '/.' '__')"
  copy_fixture "$dot_path_mutation"
  jq --arg path "$dot_path" '.origin.thirdPartyNoticePath = $path' \
    "$dot_path_mutation/upstream-tests/intellij-scala.json" \
    > "$dot_path_mutation/upstream-tests/intellij-scala.json.partial"
  mv \
    "$dot_path_mutation/upstream-tests/intellij-scala.json.partial" \
    "$dot_path_mutation/upstream-tests/intellij-scala.json"
  expect_verification_failure_with_message "$dot_path_mutation" "non-canonical path: $dot_path"
done

selection_mutation="$temporary_root/selection-mutation"
copy_fixture "$selection_mutation"
jq '.invocations = .invocations[:-1]' \
  "$selection_mutation/upstream-tests/intellij-scala-selection.json" \
  > "$selection_mutation/upstream-tests/intellij-scala-selection.json.partial"
mv \
  "$selection_mutation/upstream-tests/intellij-scala-selection.json.partial" \
  "$selection_mutation/upstream-tests/intellij-scala-selection.json"
expect_verification_failure "$selection_mutation"

printf '%s\n' 'copied IntelliJ test mutation checks passed'

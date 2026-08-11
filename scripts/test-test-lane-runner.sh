#!/usr/bin/env bash

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
python3 "$repo_root/scripts/test_test_lane_mapping.py"

temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/metallurgy-test-runner.XXXXXX")
evidence_root="$temporary_root/evidence"

cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

run_plan() {
  local run_id=$1
  METALLURGY_TEST_EVIDENCE_DIR="$evidence_root" \
    "$repo_root/scripts/run-test-lane.sh" \
    "$repo_root/test-lanes/ci.txt" \
    --plan-only \
    --run-id "$run_id"
}

run_plan first
run_plan second

(
  cd "$temporary_root"
  METALLURGY_TEST_EVIDENCE_DIR="$evidence_root" \
    "$repo_root/scripts/run-test-lane.sh" \
    "$repo_root/test-lanes/ci.txt" \
    --plan-only \
    --run-id unrelated
)

first_selection="$evidence_root/ci/first/selection.json"
second_selection="$evidence_root/ci/second/selection.json"
unrelated_selection="$evidence_root/ci/unrelated/selection.json"
cmp "$first_selection" "$second_selection" || {
  printf '%s\n' 'identical plans produced different selections' >&2
  exit 1
}
cmp "$first_selection" "$unrelated_selection" || {
  printf '%s\n' 'unrelated working directory changed the selection' >&2
  exit 1
}

invalid_manifest="$temporary_root/unsorted.txt"
printf '%s\n' \
  'com.hmemcpy.metallurgy.testkit.ExpectedOutputParserTest' \
  'com.hmemcpy.metallurgy.SmokeTest' > "$invalid_manifest"
printf '%s\t%s\n' \
  'com.hmemcpy.metallurgy.SmokeTest' \
  'testSmoke' > "${invalid_manifest%.txt}.invocations.txt"

if METALLURGY_TEST_EVIDENCE_DIR="$evidence_root" \
  "$repo_root/scripts/run-test-lane.sh" "$invalid_manifest" --plan-only --run-id unsorted; then
  printf '%s\n' 'unsorted manifest was accepted' >&2
  exit 1
fi

missing_manifest="$temporary_root/missing.txt"
printf '%s\n' 'com.hmemcpy.metallurgy.DoesNotExistTest' > "$missing_manifest"
printf '%s\t%s\n' \
  'com.hmemcpy.metallurgy.DoesNotExistTest' \
  'testMissing' > "${missing_manifest%.txt}.invocations.txt"

if METALLURGY_TEST_EVIDENCE_DIR="$evidence_root" \
  "$repo_root/scripts/run-test-lane.sh" "$missing_manifest" --plan-only --run-id missing; then
  printf '%s\n' 'missing selected suite was accepted' >&2
  exit 1
fi

fake_bin="$temporary_root/bin"
mkdir -p "$fake_bin"
fake_sbt="$fake_bin/sbt"
cp "$repo_root/scripts/fixtures/fake-test-sbt.sh" "$fake_sbt"
chmod +x "$fake_sbt"

timeout_manifest="$temporary_root/timeout.txt"
printf '%s\n' \
  'com.hmemcpy.metallurgy.ATimeoutTest' \
  'com.hmemcpy.metallurgy.BFailedTest' \
  'com.hmemcpy.metallurgy.CZeroInvocationsTest' \
  'com.hmemcpy.metallurgy.ZPassedTest' > "$timeout_manifest"
printf '%s\t%s\n' \
  'com.hmemcpy.metallurgy.ATimeoutTest' 'test' \
  'com.hmemcpy.metallurgy.BFailedTest' 'test' \
  'com.hmemcpy.metallurgy.CZeroInvocationsTest' 'test' \
  'com.hmemcpy.metallurgy.ZPassedTest' 'test' > "${timeout_manifest%.txt}.invocations.txt"

if PATH="$fake_bin:$PATH" METALLURGY_TEST_EVIDENCE_DIR="$evidence_root" \
  "$repo_root/scripts/run-test-lane.sh" \
  "$timeout_manifest" \
  --run-id timeout \
  --timeout-seconds 1; then
  printf '%s\n' 'timed-out lane reported success' >&2
  exit 1
fi

timeout_summary="$evidence_root/timeout/timeout/summary.json"
jq -e '
  .status == "failed"
  and .totals.suites == 4
  and .totals.timedOut == 1
  and .totals.failed == 1
  and .totals.passed == 1
  and .totals.orchestrationFailures == 1
  and .shards[0].status == "timeout"
  and .shards[1].status == "failed"
  and .shards[1].junit.failures == 1
  and .shards[2].status == "invocation-mismatch"
  and .shards[2].junit.missingInvocations == 1
  and .shards[3].status == "passed"
  and all(.shards[]; (.evidence.stdoutSha256 | length) == 64)
  and all(.shards[]; (.evidence.stderrSha256 | length) == 64)
' "$timeout_summary" >/dev/null

printf '%s\n' 'deterministic test-lane runner checks passed'

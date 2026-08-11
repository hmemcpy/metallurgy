#!/usr/bin/env bash

set -uo pipefail

usage() {
  printf '%s\n' \
    'Usage: scripts/run-test-lane.sh <manifest> [--plan-only] [--run-id <id>] [--timeout-seconds <seconds>]' \
    '' \
    'Environment:' \
    '  JAVA_HOME                         Verified IntelliJ SDK JBR home (required)' \
    '  METALLURGY_INTELLIJ_HOME          Exact IntelliJ SDK home (optional when derivable from JAVA_HOME)' \
    '  METALLURGY_TEST_EVIDENCE_DIR      Evidence root (default: target/test-evidence)' \
    '  METALLURGY_TEST_RUN_ID            Run identity when --run-id is omitted' \
    '  METALLURGY_TEST_SHARD_TIMEOUT_SECONDS  Per-class timeout (default: 120)' \
    '  METALLURGY_TEST_INVENTORY_TIMEOUT_SECONDS  Discovery timeout (default: 120)'
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "required command not found: $1"
}

require_positive_integer() {
  local label=$1
  local value=$2
  case "$value" in
    ''|*[!0-9]*) fail "$label must be a positive integer" ;;
  esac
  [ "$value" -gt 0 ] || fail "$label must be a positive integer"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [ "$#" -lt 1 ]; then
  usage
  exit 2
fi

manifest_argument=$1
case "$manifest_argument" in
  /*) ;;
  *) manifest_argument="$PWD/$manifest_argument" ;;
esac
shift
plan_only=false
run_id=${METALLURGY_TEST_RUN_ID:-}
shard_timeout=${METALLURGY_TEST_SHARD_TIMEOUT_SECONDS:-120}
inventory_timeout=${METALLURGY_TEST_INVENTORY_TIMEOUT_SECONDS:-120}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --plan-only)
      plan_only=true
      shift
      ;;
    --run-id)
      [ "$#" -ge 2 ] || fail '--run-id requires a value'
      run_id=$2
      shift 2
      ;;
    --timeout-seconds)
      [ "$#" -ge 2 ] || fail '--timeout-seconds requires a value'
      shard_timeout=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

require_positive_integer timeout "$shard_timeout"
require_positive_integer 'inventory timeout' "$inventory_timeout"

require_command git
require_command jq
require_command sbt
require_command awk
require_command grep
require_command sed
require_command sort
require_command comm
require_command python3

if [ -x /opt/homebrew/bin/gtimeout ]; then
  timeout_command=/opt/homebrew/bin/gtimeout
elif command -v gtimeout >/dev/null 2>&1; then
  timeout_command=$(command -v gtimeout)
elif command -v timeout >/dev/null 2>&1 && timeout --version 2>&1 | grep -q 'GNU coreutils'; then
  timeout_command=$(command -v timeout)
else
  fail 'GNU timeout is required (gtimeout on macOS, timeout on Linux)'
fi

[ -n "${JAVA_HOME:-}" ] || fail 'JAVA_HOME must point to the verified IntelliJ SDK JBR'
[ -x "$JAVA_HOME/bin/java" ] || fail "JAVA_HOME has no executable java: $JAVA_HOME"

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root" || fail "cannot enter repository root: $repo_root"
intellij_home=${METALLURGY_INTELLIJ_HOME:-}
if [ -z "$intellij_home" ]; then
  case "$JAVA_HOME" in
    */jbr/Contents/Home) intellij_home=${JAVA_HOME%/jbr/Contents/Home} ;;
    */jbr) intellij_home=${JAVA_HOME%/jbr} ;;
    *) fail 'METALLURGY_INTELLIJ_HOME is required when JAVA_HOME is not below <intellij-home>/jbr' ;;
  esac
fi
"$JAVA_HOME/bin/java" "$repo_root/scripts/MetallurgyBaselineVerifier.java" host "$intellij_home" ||
  fail "JAVA_HOME and IntelliJ SDK do not match the Metallurgy baseline: $intellij_home"

manifest_directory=$(cd "$(dirname "$manifest_argument")" && pwd)
manifest="$manifest_directory/$(basename "$manifest_argument")"
[ -f "$manifest" ] || fail "manifest not found: $manifest"
case "$manifest" in
  *.txt) invocation_manifest=${manifest%.txt}.invocations.txt ;;
  *) fail "manifest must have a .txt extension: $manifest" ;;
esac
[ -f "$invocation_manifest" ] || fail "invocation manifest not found: $invocation_manifest"

lane=$(basename "$manifest" .txt)
case "$lane" in
  ''|*[!A-Za-z0-9._-]*) fail "invalid lane name: $lane" ;;
esac

python3 "$repo_root/scripts/test_lane_mapping.py" check --lane "$manifest" ||
  fail "lane mapping check failed: $lane"

if [ -z "$run_id" ]; then
  run_id="$(date -u '+%Y%m%dT%H%M%SZ')-$$"
fi
case "$run_id" in
  ''|*[!A-Za-z0-9._-]*) fail "invalid run id: $run_id" ;;
esac

if ! LC_ALL=C sort -c "$manifest" >/dev/null 2>&1; then
  fail "manifest must be sorted bytewise: $manifest"
fi
duplicate=$(LC_ALL=C uniq -d "$manifest" | head -1)
[ -z "$duplicate" ] || fail "manifest contains duplicate suite: $duplicate"

classes=()
while IFS= read -r class_name || [ -n "$class_name" ]; do
  [ -n "$class_name" ] || fail "manifest contains a blank line: $manifest"
  case "$class_name" in
    *[!A-Za-z0-9_.$]*) fail "invalid suite name in manifest: $class_name" ;;
  esac
  classes[${#classes[@]}]=$class_name
done < "$manifest"
[ "${#classes[@]}" -gt 0 ] || fail "manifest is empty: $manifest"

if ! LC_ALL=C sort -c "$invocation_manifest" >/dev/null 2>&1; then
  fail "invocation manifest must be sorted bytewise: $invocation_manifest"
fi
duplicate=$(LC_ALL=C uniq -d "$invocation_manifest" | head -1)
[ -z "$duplicate" ] || fail "invocation manifest contains duplicate identity: $duplicate"
awk -F '\t' '
  NF != 2 || $1 !~ /^[A-Za-z0-9_.$]+$/ || $2 == "" { invalid = 1 }
  END { exit invalid }
' "$invocation_manifest" || fail "invalid invocation identity in manifest: $invocation_manifest"
invocation_suites=$(mktemp "${TMPDIR:-/tmp}/metallurgy-invocation-suites.XXXXXX")
cut -f 1 "$invocation_manifest" | LC_ALL=C sort -u > "$invocation_suites"
if ! cmp "$manifest" "$invocation_suites" >/dev/null 2>&1; then
  rm -f "$invocation_suites"
  fail "suite and invocation manifests do not have the same classes"
fi
rm -f "$invocation_suites"

evidence_root=${METALLURGY_TEST_EVIDENCE_DIR:-"$repo_root/target/test-evidence"}
run_directory="$evidence_root/$lane/$run_id"
[ ! -e "$run_directory" ] || fail "evidence directory already exists: $run_directory"

mkdir -p "$run_directory/discovery" "$run_directory/shards"
cp "$manifest" "$run_directory/manifest.txt"
cp "$invocation_manifest" "$run_directory/invocations.txt"
printf '%s\n' running > "$run_directory/run-state.txt"
git -C "$repo_root" rev-parse HEAD > "$run_directory/source-revision.txt"
git -C "$repo_root" status --porcelain=v1 > "$run_directory/git-status.txt"
git -C "$repo_root" diff --binary HEAD > "$run_directory/source.patch"

manifest_hash=$(sha256_file "$manifest")
invocation_manifest_hash=$(sha256_file "$invocation_manifest")
inventory_file="$run_directory/discovery/defined-tests.txt"
environment_file="$run_directory/environment.properties"
classpath_file="$run_directory/classpath.sha256"

"$timeout_command" --kill-after=5s "${inventory_timeout}s" \
  env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" \
  sbt \
    "-Dmetallurgy.test.inventory=$inventory_file" \
    "-Dmetallurgy.test.environment=$environment_file" \
    "-Dmetallurgy.test.classpath=$classpath_file" \
    -batch -no-colors writeTestInventory \
    >"$run_directory/discovery/stdout.log" \
    2>"$run_directory/discovery/stderr.log"
inventory_exit=$?
printf '%s\n' "$inventory_exit" > "$run_directory/discovery/exit-code.txt"

if [ "$inventory_exit" -ne 0 ]; then
  if [ "$inventory_exit" -eq 124 ]; then
    inventory_status=inventory-timeout
  else
    inventory_status=inventory-failed
  fi
  jq -n \
    --arg lane "$lane" \
    --arg runId "$run_id" \
    --arg status "$inventory_status" \
    --argjson exitCode "$inventory_exit" \
    '{schemaVersion: 1, lane: $lane, runId: $runId, status: $status, inventoryExitCode: $exitCode}' \
    > "$run_directory/summary.json.partial"
  mv "$run_directory/summary.json.partial" "$run_directory/summary.json"
  printf '%s\n' "$inventory_status" > "$run_directory/run-state.txt"
  printf 'Inventory failed for %s; evidence: %s\n' "$lane" "$run_directory" >&2
  exit 1
fi

[ -f "$inventory_file" ] || fail "inventory task produced no suite inventory: $inventory_file"
[ -f "$environment_file" ] || fail "inventory task produced no environment record: $environment_file"
[ -f "$classpath_file" ] || fail "inventory task produced no classpath record: $classpath_file"

if ! LC_ALL=C sort -c "$inventory_file" >/dev/null 2>&1; then
  fail "discovered suite inventory is not sorted: $inventory_file"
fi

missing_file="$run_directory/discovery/missing-selected-tests.txt"
LC_ALL=C comm -23 "$manifest" "$inventory_file" > "$missing_file"
if [ -s "$missing_file" ]; then
  jq -Rn '[inputs]' < "$missing_file" > "$run_directory/discovery/missing-selected-tests.json"
  jq -n \
    --arg lane "$lane" \
    --arg runId "$run_id" \
    --slurpfile missing "$run_directory/discovery/missing-selected-tests.json" \
    '{schemaVersion: 1, lane: $lane, runId: $runId, status: "selection-missing", missing: $missing[0]}' \
    > "$run_directory/summary.json.partial"
  mv "$run_directory/summary.json.partial" "$run_directory/summary.json"
  printf '%s\n' selection-missing > "$run_directory/run-state.txt"
  printf 'Selected suites are absent from discovery; evidence: %s\n' "$run_directory" >&2
  exit 1
fi

classpath_hash=$(sha256_file "$classpath_file")
inventory_hash=$(sha256_file "$inventory_file")
script_hash=$(sha256_file "$repo_root/scripts/run-test-lane.sh")
report_parser_hash=$(sha256_file "$repo_root/scripts/TestReportInvocations.java")
classes_json=$(printf '%s\n' "${classes[@]}" | jq -R . | jq -s .)

jq -n \
  --arg lane "$lane" \
  --arg manifestSha256 "$manifest_hash" \
  --arg invocationManifestSha256 "$invocation_manifest_hash" \
  --arg inventorySha256 "$inventory_hash" \
  --arg classpathSha256 "$classpath_hash" \
  --arg runnerSha256 "$script_hash" \
  --arg reportParserSha256 "$report_parser_hash" \
  --argjson timeoutSeconds "$shard_timeout" \
  --argjson classes "$classes_json" \
  '{
    schemaVersion: 1,
    lane: $lane,
    manifestSha256: $manifestSha256,
    invocationManifestSha256: $invocationManifestSha256,
    discoveredInventorySha256: $inventorySha256,
    classpathSha256: $classpathSha256,
    runnerSha256: $runnerSha256,
    reportParserSha256: $reportParserSha256,
    shardPolicy: "one-suite-per-process",
    timeoutSeconds: $timeoutSeconds,
    classes: $classes
  }' > "$run_directory/selection.json"

jq -Rn \
  --arg os "$(uname -s)" \
  --arg architecture "$(uname -m)" \
  '[inputs | capture("^(?<key>[^=]+)=(?<value>.*)$")] | from_entries
   + {os: $os, architecture: $architecture}' \
  < "$environment_file" > "$run_directory/environment.json"

if [ "$plan_only" = true ]; then
  jq -n \
    --arg lane "$lane" \
    --arg runId "$run_id" \
    --slurpfile selection "$run_directory/selection.json" \
    --slurpfile environment "$run_directory/environment.json" \
    '{
      schemaVersion: 1,
      lane: $lane,
      runId: $runId,
      status: "planned",
      selection: $selection[0],
      environment: $environment[0]
    }' > "$run_directory/summary.json.partial"
  mv "$run_directory/summary.json.partial" "$run_directory/summary.json"
  printf '%s\n' planned > "$run_directory/run-state.txt"
  printf 'Planned %s suites for %s; evidence: %s\n' "${#classes[@]}" "$lane" "$run_directory"
  exit 0
fi

result_files=()
index=0
for class_name in "${classes[@]}"; do
  index=$((index + 1))
  class_slug=$(printf '%s' "$class_name" | tr '.$' '__')
  shard_name=$(printf 'shard-%04d-%s' "$index" "$class_slug")
  shard_directory="$run_directory/shards/$shard_name"
  reports_directory="$shard_directory/junit"
  test_root="$shard_directory/intellij"
  mkdir -p "$reports_directory" "$test_root"

  printf '%s\n' "$class_name" > "$shard_directory/suite.txt"
  started_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  started_seconds=$(date '+%s')

  "$timeout_command" --kill-after=5s "${shard_timeout}s" \
    env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" \
    sbt \
      "-Dmetallurgy.test.root=$test_root" \
      "-Dmetallurgy.test.reports=$reports_directory" \
      -batch -no-colors "testOnly $class_name" \
      >"$shard_directory/stdout.log" \
      2>"$shard_directory/stderr.log"
  exit_code=$?

  finished_seconds=$(date '+%s')
  finished_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  duration_seconds=$((finished_seconds - started_seconds))
  printf '%s\n' "$exit_code" > "$shard_directory/exit-code.txt"

  expected_report="$reports_directory/TEST-$class_name.xml"
  report_count=$(find "$reports_directory" -maxdepth 1 -type f -name 'TEST-*.xml' | wc -l | tr -d ' ')
  if [ -f "$expected_report" ]; then
    unexpected_reports=$((report_count - 1))
  else
    unexpected_reports=$report_count
  fi

  tests=0
  failures=0
  errors=0
  skipped=0
  expected_invocations="$shard_directory/expected-invocations.txt"
  actual_invocations="$shard_directory/actual-invocations.txt"
  missing_invocations="$shard_directory/missing-invocations.txt"
  unexpected_invocations="$shard_directory/unexpected-invocations.txt"
  report_summary="$shard_directory/junit-summary.properties"
  awk -F '\t' -v suite="$class_name" '$1 == suite' "$invocation_manifest" > "$expected_invocations"
  : > "$actual_invocations"
  : > "$missing_invocations"
  : > "$unexpected_invocations"
  invocation_parse_exit=0
  if [ -f "$expected_report" ]; then
    "$timeout_command" --kill-after=5s 30s \
      "$JAVA_HOME/bin/java" "$repo_root/scripts/TestReportInvocations.java" "$expected_report" "$report_summary" \
      > "$actual_invocations" 2> "$shard_directory/invocation-parser.stderr.log"
    invocation_parse_exit=$?
    if [ "$invocation_parse_exit" -eq 0 ]; then
      tests=$(wc -l < "$actual_invocations" | tr -d ' ')
      failures=$(sed -n 's/^failures=//p' "$report_summary")
      errors=$(sed -n 's/^errors=//p' "$report_summary")
      skipped=$(sed -n 's/^skipped=//p' "$report_summary")
      LC_ALL=C comm -23 "$expected_invocations" "$actual_invocations" > "$missing_invocations"
      LC_ALL=C comm -13 "$expected_invocations" "$actual_invocations" > "$unexpected_invocations"
    fi
  fi
  missing_invocation_count=$(wc -l < "$missing_invocations" | tr -d ' ')
  unexpected_invocation_count=$(wc -l < "$unexpected_invocations" | tr -d ' ')

  if [ "$exit_code" -eq 124 ] || { [ "$exit_code" -eq 137 ] && [ "$duration_seconds" -ge "$shard_timeout" ]; }; then
    shard_status=timeout
  elif [ ! -f "$expected_report" ]; then
    shard_status=missing-report
  elif [ "$invocation_parse_exit" -ne 0 ]; then
    shard_status=invalid-report
  elif [ "$unexpected_reports" -ne 0 ] || [ "$report_count" -ne 1 ]; then
    shard_status=unexpected-report
  elif [ "$missing_invocation_count" -ne 0 ] || [ "$unexpected_invocation_count" -ne 0 ]; then
    shard_status=invocation-mismatch
  elif [ "$exit_code" -eq 0 ] && { [ "$failures" -ne 0 ] || [ "$errors" -ne 0 ]; }; then
    shard_status=masked-failure
  elif [ "$exit_code" -eq 0 ]; then
    shard_status=passed
  elif [ "$failures" -ne 0 ] || [ "$errors" -ne 0 ]; then
    shard_status=failed
  else
    shard_status=process-failed
  fi

  stdout_sha256=$(sha256_file "$shard_directory/stdout.log")
  stderr_sha256=$(sha256_file "$shard_directory/stderr.log")
  if [ -f "$expected_report" ]; then
    junit_sha256=$(sha256_file "$expected_report")
  else
    junit_sha256=
  fi
  idea_log="$test_root/system/log/idea.log"
  if [ -f "$idea_log" ]; then
    idea_log_present=true
    idea_log_sha256=$(sha256_file "$idea_log")
  else
    idea_log_present=false
    idea_log_sha256=
  fi

  result_file="$shard_directory/result.json"
  jq -n \
    --arg suite "$class_name" \
    --arg status "$shard_status" \
    --arg startedAt "$started_at" \
    --arg finishedAt "$finished_at" \
    --arg stdoutSha256 "$stdout_sha256" \
    --arg stderrSha256 "$stderr_sha256" \
    --arg junitSha256 "$junit_sha256" \
    --arg ideaLogSha256 "$idea_log_sha256" \
    --argjson ideaLogPresent "$idea_log_present" \
    --argjson index "$index" \
    --argjson exitCode "$exit_code" \
    --argjson durationSeconds "$duration_seconds" \
    --argjson reportCount "$report_count" \
    --argjson unexpectedReports "$unexpected_reports" \
    --argjson missingInvocations "$missing_invocation_count" \
    --argjson unexpectedInvocations "$unexpected_invocation_count" \
    --argjson tests "$tests" \
    --argjson failures "$failures" \
    --argjson errors "$errors" \
    --argjson skipped "$skipped" \
    '{
      index: $index,
      suite: $suite,
      status: $status,
      exitCode: $exitCode,
      startedAt: $startedAt,
      finishedAt: $finishedAt,
      durationSeconds: $durationSeconds,
      evidence: {
        stdoutSha256: $stdoutSha256,
        stderrSha256: $stderrSha256,
        junitSha256: $junitSha256,
        ideaLogPresent: $ideaLogPresent,
        ideaLogSha256: $ideaLogSha256
      },
      junit: {
        reportCount: $reportCount,
        unexpectedReports: $unexpectedReports,
        missingInvocations: $missingInvocations,
        unexpectedInvocations: $unexpectedInvocations,
        tests: $tests,
        failures: $failures,
        errors: $errors,
        skipped: $skipped
      }
    }' > "$result_file"
  result_files[${#result_files[@]}]=$result_file
  printf '[%d/%d] %s: %s (%ss)\n' \
    "$index" "${#classes[@]}" "$class_name" "$shard_status" "$duration_seconds"
done

if [ "${#result_files[@]}" -ne "${#classes[@]}" ]; then
  fail "result closure failed: expected ${#classes[@]}, got ${#result_files[@]}"
fi

jq -s \
  --arg lane "$lane" \
  --arg runId "$run_id" \
  --arg sourceRevision "$(cat "$run_directory/source-revision.txt")" \
  --slurpfile selection "$run_directory/selection.json" \
  --slurpfile environment "$run_directory/environment.json" \
  '{
    schemaVersion: 1,
    lane: $lane,
    runId: $runId,
    sourceRevision: $sourceRevision,
    status: (if all(.[]; .status == "passed") then "passed" else "failed" end),
    selection: $selection[0],
    environment: $environment[0],
    totals: {
      suites: length,
      passed: map(select(.status == "passed")) | length,
      failed: map(select(.status == "failed")) | length,
      timedOut: map(select(.status == "timeout")) | length,
      orchestrationFailures:
        map(select(.status != "passed" and .status != "failed" and .status != "timeout")) | length,
      tests: map(.junit.tests) | add,
      failures: map(.junit.failures) | add,
      errors: map(.junit.errors) | add,
      skipped: map(.junit.skipped) | add
    },
    shards: .
  }' "${result_files[@]}" > "$run_directory/summary.json.partial"

mv "$run_directory/summary.json.partial" "$run_directory/summary.json"
final_status=$(jq -r '.status' "$run_directory/summary.json")
printf '%s\n' "$final_status" > "$run_directory/run-state.txt"
printf 'Completed %s with status %s; evidence: %s\n' "$lane" "$final_status" "$run_directory"

if [ "$final_status" = passed ]; then
  exit 0
else
  exit 1
fi

#!/usr/bin/env bash

set -euo pipefail

inventory=
environment=
classpath=
reports=
command_name=

for argument in "$@"; do
  case "$argument" in
    -Dmetallurgy.test.inventory=*) inventory=${argument#*=} ;;
    -Dmetallurgy.test.environment=*) environment=${argument#*=} ;;
    -Dmetallurgy.test.classpath=*) classpath=${argument#*=} ;;
    -Dmetallurgy.test.reports=*) reports=${argument#*=} ;;
    writeTestInventory|testOnly*) command_name=$argument ;;
  esac
done

if [ "$command_name" = writeTestInventory ]; then
  mkdir -p "$(dirname "$inventory")"
  printf '%s\n' \
    'com.hmemcpy.metallurgy.ATimeoutTest' \
    'com.hmemcpy.metallurgy.BFailedTest' \
    'com.hmemcpy.metallurgy.CZeroInvocationsTest' \
    'com.hmemcpy.metallurgy.ZPassedTest' > "$inventory"
  printf '%s\n' \
    'intellij.build=fake' \
    'java.runtime.version=fake' \
    'java.vendor=JetBrains s.r.o.' > "$environment"
  printf '%s\n' 'fake	/fake/classpath' > "$classpath"
  exit 0
fi

suite=${command_name#testOnly }
if [ "$suite" = com.hmemcpy.metallurgy.ATimeoutTest ]; then
  sleep 5
  exit 0
fi

mkdir -p "$reports"
if [ "$suite" = com.hmemcpy.metallurgy.BFailedTest ]; then
  printf '<testsuite tests="1" failures="1" errors="0" skipped="0"><testcase classname="%s" name="test"><failure message="failed"/></testcase></testsuite>\n' \
    "$suite" > "$reports/TEST-$suite.xml"
  exit 1
fi
if [ "$suite" = com.hmemcpy.metallurgy.CZeroInvocationsTest ]; then
  printf '<testsuite tests="0" failures="0" errors="0" skipped="0"></testsuite>\n' \
    > "$reports/TEST-$suite.xml"
  exit 0
fi
printf '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="%s" name="test"/></testsuite>\n' \
  "$suite" > "$reports/TEST-$suite.xml"

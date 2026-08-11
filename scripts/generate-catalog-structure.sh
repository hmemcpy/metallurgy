#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 1 || ! $1 =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "usage: scripts/generate-catalog-structure.sh <run-id>" >&2
  exit 2
fi

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

run_id=$1
output="target/catalog-structure/$run_id"
gtimeout=${GTIMEOUT:-/opt/homebrew/bin/gtimeout}

if [[ -e $output ]]; then
  echo "catalog structure output already exists: $output" >&2
  exit 1
fi
if [[ -z ${JAVA_HOME:-} || ! -x $JAVA_HOME/bin/java ]]; then
  echo "JAVA_HOME must name the exact manifest JBR" >&2
  exit 1
fi
if [[ ! -x $gtimeout ]]; then
  echo "GNU gtimeout is unavailable: $gtimeout" >&2
  exit 1
fi

case "$JAVA_HOME" in
  */jbr/Contents/Home) intellij_home=${JAVA_HOME%/jbr/Contents/Home} ;;
  */jbr) intellij_home=${JAVA_HOME%/jbr} ;;
  *)
    echo "JAVA_HOME must be the JBR below the exact IntelliJ SDK" >&2
    exit 1
    ;;
esac
"$JAVA_HOME/bin/java" "$repo_root/scripts/MetallurgyBaselineVerifier.java" host "$intellij_home"

export METALLURGY_CATALOG_SOURCE_REVISION
METALLURGY_CATALOG_SOURCE_REVISION=$(git rev-parse HEAD)
export METALLURGY_CATALOG_SOURCE_TREE
METALLURGY_CATALOG_SOURCE_TREE=$(git rev-parse 'HEAD^{tree}')
export METALLURGY_CATALOG_SOURCE_STATUS
if [[ -n $(git status --porcelain=v1) ]]; then
  echo "catalog structure generation requires a clean worktree" >&2
  exit 1
fi
METALLURGY_CATALOG_SOURCE_STATUS=clean
export METALLURGY_CATALOG_JBR
METALLURGY_CATALOG_JBR=$JAVA_HOME

METALLURGY_CATALOG_STRUCTURE_RUN_ID=$run_id \
  "$gtimeout" --kill-after=5s 120s \
  env JAVA_HOME="$JAVA_HOME" PATH="$JAVA_HOME/bin:$PATH" \
  sbt -batch -no-colors \
  "testOnly com.hmemcpy.metallurgy.psiproducer.Scala3PsiProductionCatalogTest"

test -s "$output/persisted-schema.tsv"
test -s "$output/catalog-plan.tsv"
test -s "$output/representative-whole-file-plan-modifier-annotation.tsv"
test -s "$output/fingerprints.txt"
(cd "$output" && shasum -a 256 -c SHA256SUMS)

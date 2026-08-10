#!/usr/bin/env bash

set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
temporary_root=$(mktemp -d "${TMPDIR:-/tmp}/metallurgy-baseline-verifier.XXXXXX")
classes="$temporary_root/classes"
fixture="$temporary_root/repository"
real_sdk=${METALLURGY_INTELLIJ_HOME:-"$HOME/.metallurgyPluginIC/sdk/$(java "$repo_root/scripts/MetallurgyBaselineVerifier.java" value intellij.build)"}
if [[ -d "$real_sdk/jbr/Contents/Home" ]]; then
  real_jbr="$real_sdk/jbr/Contents/Home"
else
  real_jbr="$real_sdk/jbr"
fi

cleanup() {
  rm -rf -- "$temporary_root"
}
trap cleanup EXIT

mkdir -p "$classes" "$fixture"
javac -d "$classes" "$repo_root/scripts/MetallurgyBaselineVerifier.java"

files=(
  .agents/setup
  .agents/resume
  .github/workflows/ci.yml
  AGENTS.md
  build.sbt
  project/metallurgy-baseline.properties
  project/build.properties
  testkit/build.sbt
  testkit/project/build.properties
  testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt
  testkit/src/main/scala/org/jetbrains/plugins/scala/util/runners/TestScalaVersion.java
  ideprobe-tests/build.sbt
  ideprobe-tests/project/build.properties
  ideprobe-tests/src/test/resources/ideprobe.conf
  ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/ProjectLifecycleTest.scala
  dogfood/build.sbt
  dogfood/project/build.properties
  scripts/run-test-lane.sh
  docs/scala3-compiler-backend.md
  docs/deterministic-scala3-psi-implementation-program.md
  docs/agents/hash-provenance.md
)
for file in "${files[@]}"; do
  mkdir -p "$fixture/$(dirname "$file")"
  cp "$repo_root/$file" "$fixture/$file"
done

verify() {
  java -Dmetallurgy.repo.root="$1" -cp "$classes" MetallurgyBaselineVerifier "${@:2}"
}

expect_failure() {
  local name=$1
  local expected=$2
  shift 2
  local output="$temporary_root/$name.out"
  if "$@" >"$output" 2>&1; then
    printf 'expected failure passed: %s\n' "$name" >&2
    exit 1
  fi
  grep -F "$expected" "$output" >/dev/null || {
    printf 'failure %s did not contain %s\n' "$name" "$expected" >&2
    cat "$output" >&2
    exit 1
  }
  grep -F 'Missing coordinates' "$output" >/dev/null
  grep -F 'Extra coordinates' "$output" >/dev/null
  grep -F 'Mismatched consumers' "$output" >/dev/null
}

mutate_manifest() {
  local name=$1
  shift
  local copy="$temporary_root/$name"
  cp -R "$fixture" "$copy"
  "$@" "$copy/project/metallurgy-baseline.properties"
  expect_failure "$name" project/metallurgy-baseline.properties verify "$copy" static
}

verify "$fixture" static >/dev/null
[[ "$(verify "$fixture" value scala.compiler.version)" == "3.7.4" ]]
expect_failure unknown-value 'unknown coordinate' verify "$fixture" value unknown.coordinate

(cd /tmp && "$real_jbr/bin/java" "$repo_root/scripts/MetallurgyBaselineVerifier.java" value intellij.build) \
  > "$temporary_root/cwd-value.txt"
[[ "$(cat "$temporary_root/cwd-value.txt")" == "261.26222.65" ]]

mutate_manifest duplicate sh -c 'sed -n "1p" "$1" >> "$1"' sh
mutate_manifest missing sed -i.bak '/^ide\.probe\.version=/d'
mutate_manifest extra sh -c '{ sed -n "1p" "$1"; printf "%s\n" "extra.coordinate=value"; sed -n "2,999p" "$1"; } > "$1.new" && mv "$1.new" "$1"' sh
mutate_manifest empty sed -i.bak 's/^ide\.probe\.version=.*/ide.probe.version=/'
mutate_manifest unsorted sh -c '{ sed -n "2p" "$1"; sed -n "1p" "$1"; sed -n "3,999p" "$1"; } > "$1.new" && mv "$1.new" "$1"' sh
mutate_manifest padded sed -i.bak 's/^ide\.probe\.version=.*/ide.probe.version=0.53.0 /'
mutate_manifest continued sed -i.bak 's/^ide\.probe\.version=.*/ide.probe.version=0.53.0\\/'
mutate_manifest escaped sed -i.bak 's/^jbr\.java\.vendor=.*/jbr.java.vendor=JetBrains\\ s.r.o./'
mutate_manifest malformed sed -i.bak 's/^ide\.probe\.version=.*/ide.probe.version 0.53.0/'
mutate_manifest malformed-value sed -i.bak 's/^java\.bytecode\.release=.*/java.bytecode.release=seventeen/'
mutate_manifest non-ascii sh -c 'printf "nonascii.coordinate=é\n" >> "$1"' sh
mutate_manifest missing-final-lf sh -c 'content=$(cat "$1"); printf "%s" "$content" > "$1"' sh
mutate_manifest empty-row sh -c '{ sed -n "1p" "$1"; printf "\n"; sed -n "2,999p" "$1"; } > "$1.new" && mv "$1.new" "$1"' sh
mutate_manifest multiple-separators sed -i.bak 's/^ide\.probe\.version=.*/ide.probe.version=0.53.0=extra/'
mutate_manifest invalid-key sed -i.bak 's/^ide\.probe\.version=.*/Ide.probe.version=0.53.0/'

consumer="$temporary_root/wrong-consumer"
cp -R "$fixture" "$consumer"
sed -i.bak 's/metallurgyBaseline.value("scala.compiler.version")/metallurgyBaseline.value("testkit.scala.version")/' "$consumer/build.sbt"
expect_failure wrong-consumer 'coordinate=scala.compiler.version' verify "$consumer" static

duplicate="$temporary_root/unknown-duplicate"
cp -R "$fixture" "$duplicate"
printf '%s\n' '261.26222.65' > "$duplicate/.agents/unknown-baseline"
expect_failure unknown-duplicate 'uncataloged current value' verify "$duplicate" static

dogfood="$temporary_root/wrong-dogfood"
cp -R "$fixture" "$dogfood"
printf '%s\n' 'sbt.version=1.11.7' > "$dogfood/dogfood/project/build.properties"
expect_failure wrong-dogfood 'coordinate=dogfood.sbt.version' verify "$dogfood" static

host="$temporary_root/host"
mkdir -p "$host/custom-plugins/Scala/lib"
cp "$real_sdk/product-info.json" "$host/product-info.json"
cp "$real_sdk/custom-plugins/Scala/lib/pluginXml.jar" "$host/custom-plugins/Scala/lib/pluginXml.jar"
ln -s "$real_sdk/jbr" "$host/jbr"
"$real_jbr/bin/java" -Dmetallurgy.repo.root="$fixture" -cp "$classes" MetallurgyBaselineVerifier host "$host" >/dev/null

host_mutation() {
  local name=$1
  local expected=$2
  shift 2
  local copy="$temporary_root/host-$name"
  cp -R "$host" "$copy"
  "$@" "$copy"
  expect_failure "host-$name" "$expected" \
    "$real_jbr/bin/java" -Dmetallurgy.repo.root="$fixture" -cp "$classes" MetallurgyBaselineVerifier host "$copy"
}

json_mutation() {
  local replacement=$1 home=$2
  sed -i.bak "$replacement" "$home/product-info.json"
}
host_mutation product-code 'coordinate=intellij.product.code' json_mutation 's/"productCode": "IC"/"productCode": "IU"/'
host_mutation build 'coordinate=intellij.build' json_mutation 's/"buildNumber": "261.26222.65"/"buildNumber": "999.1"/'
host_mutation release 'coordinate=intellij.release' json_mutation 's/"version": "2026.1.4"/"version": "999.1"/'

plugin_mutation() {
  local field=$1 replacement=$2 home=$3
  local unpack="$temporary_root/plugin-$field"
  mkdir -p "$unpack"
  (cd "$unpack" && jar xf "$home/custom-plugins/Scala/lib/pluginXml.jar")
  sed -i.bak "$replacement" "$unpack/META-INF/plugin.xml"
  rm "$unpack/META-INF/plugin.xml.bak"
  (cd "$unpack" && jar cf "$home/custom-plugins/Scala/lib/pluginXml.jar" .)
}
host_mutation plugin-id 'coordinate=scala.plugin.id' plugin_mutation id 's/<id>org.intellij.scala<\/id>/<id>wrong.scala<\/id>/'
host_mutation plugin-version 'coordinate=scala.plugin.version' plugin_mutation version 's/<version>2026.1.20<\/version>/<version>0<\/version>/'

fake_host() {
  local name=$1 runtime=$2 vendor=$3 vendor_version=$4
  local home="$temporary_root/$name"
  mkdir -p "$home/custom-plugins/Scala/lib" "$home/jbr/bin"
  cp "$real_sdk/product-info.json" "$home/product-info.json"
  cp "$real_sdk/custom-plugins/Scala/lib/pluginXml.jar" "$home/custom-plugins/Scala/lib/pluginXml.jar"
  cat > "$home/jbr/release" <<EOF
JAVA_RUNTIME_VERSION="$runtime"
IMPLEMENTOR="$vendor"
IMPLEMENTOR_VERSION="$vendor_version"
EOF
  cat > "$home/jbr/bin/java" <<EOF
#!/usr/bin/env sh
cat >&2 <<PROPERTIES
    java.runtime.version = $runtime
    java.vendor = $vendor
    java.vendor.version = $vendor_version
PROPERTIES
exit 0
EOF
  chmod +x "$home/jbr/bin/java"
  printf '%s' "$home"
}

generic_java=$(fake_host generic-java 25.0.3 'Eclipse Adoptium' Temurin-25)
expect_failure generic-java 'coordinate=jbr.java.vendor' \
  "$real_jbr/bin/java" -Dmetallurgy.repo.root="$fixture" -cp "$classes" MetallurgyBaselineVerifier host "$generic_java"
wrong_jbr=$(fake_host wrong-jbr 25.0.3+9-b1 'JetBrains s.r.o.' JBR-wrong)
expect_failure wrong-jbr 'coordinate=jbr.java.runtime.version' \
  "$real_jbr/bin/java" -Dmetallurgy.repo.root="$fixture" -cp "$classes" MetallurgyBaselineVerifier host "$wrong_jbr"

printf '%s\n' 'Metallurgy baseline verifier checks passed'

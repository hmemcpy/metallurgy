#!/usr/bin/env bash
set -euo pipefail

if [[ $# -eq 0 ]]; then
  echo "Usage: $0 <command> [args...]" >&2
  exit 2
fi

host_os="$(uname -s)"
host_architecture="$(uname -m)"

case "$host_os" in
  Darwin) product_os="macOS" ;;
  *) product_os="$host_os" ;;
esac

case "$host_architecture" in
  arm64) product_architecture="aarch64" ;;
  x86_64) product_architecture="amd64" ;;
  *) product_architecture="$host_architecture" ;;
esac

if [[ "$product_os" != "macOS" || "$product_architecture" != "aarch64" ]]; then
  exec "$@"
fi

if [[ "${METALLURGY_IDEA_JAVA_OPTIONS+x}" == "x" ]]; then
  echo "METALLURGY_IDEA_JAVA_OPTIONS is managed by $0; unset it and rerun" >&2
  exit 2
fi

if [[ -z "${METALLURGY_INTELLIJ_HOME:-}" ]]; then
  echo "METALLURGY_INTELLIJ_HOME is required on macOS arm64" >&2
  exit 2
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to read IntelliJ product-info.json" >&2
  exit 2
fi

if [[ ! -d "$METALLURGY_INTELLIJ_HOME" ]]; then
  echo "IntelliJ home is not a directory: $METALLURGY_INTELLIJ_HOME" >&2
  exit 2
fi

intellij_home="$(cd "$METALLURGY_INTELLIJ_HOME" && pwd -P)"
product_info="$intellij_home/product-info.json"
if [[ ! -f "$product_info" ]]; then
  echo "IntelliJ product metadata is absent: $product_info" >&2
  exit 2
fi

selection="$({
  jq -cer \
    --arg os "$product_os" \
    --arg architecture "$product_architecture" \
    '
      def required_packages:
        [
          "com.apple.eawt",
          "com.apple.eawt.event",
          "com.apple.laf",
          "sun.lwawt",
          "sun.lwawt.macosx"
        ];

      if (.launch | type) != "array" then
        error("product-info launch array is absent")
      else
        [.launch[] | select(.os == $os and .arch == $architecture)] as $matches
        | if ($matches | length) != 1 then
            error(
              "expected exactly one product-info launch entry for "
              + $os + "/" + $architecture
              + ", found " + (($matches | length) | tostring)
            )
          else
            $matches[0]
          end
        | if (.additionalJvmArguments | type) != "array" then
            error("launch entry " + $os + "/" + $architecture + " has no JVM argument array")
          else
            .additionalJvmArguments as $arguments
            | [$arguments[] | select(startswith("-Djna.boot.library.path="))] as $jna
            | if ($jna | length) != 1 then
                error(
                  "launch entry " + $os + "/" + $architecture
                  + " must contain exactly one JNA boot library option, found "
                  + (($jna | length) | tostring)
                )
              else
                required_packages
                | map("--add-opens=java.desktop/" + . + "=ALL-UNNAMED") as $required
                | [
                    $required[] as $option
                    | ($arguments | map(select(. == $option)) | length) as $count
                    | select($count != 1)
                    | $option + " (found " + ($count | tostring) + ")"
                  ] as $invalid
                | if ($invalid | length) != 0 then
                    error(
                      "launch entry " + $os + "/" + $architecture
                      + " has missing or ambiguous required JVM options: "
                      + ($invalid | join(", "))
                    )
                  else
                    {
                      jna: $jna[0],
                      apple: [
                        $arguments[] as $argument
                        | select(($required | index($argument)) != null)
                        | $argument
                      ]
                    }
                  end
              end
          end
      end
    ' \
    "$product_info"
} 2>&1)" || {
  echo "Unable to derive macOS arm64 IDE child options from $product_info:" >&2
  echo "$selection" >&2
  exit 2
}

jna_option="$(printf '%s' "$selection" | jq -er '.jna')"
jna_prefix="-Djna.boot.library.path="
jna_value="${jna_option#"$jna_prefix"}"
app_package_prefix='$APP_PACKAGE/'
idea_home_prefix='$IDE_HOME/'

case "$jna_value" in
  "$app_package_prefix"*) jna_path="$intellij_home/${jna_value#"$app_package_prefix"}" ;;
  "$idea_home_prefix"*) jna_path="$intellij_home/${jna_value#"$idea_home_prefix"}" ;;
  *)
    echo "Unsupported JNA path in $product_info: $jna_value" >&2
    exit 2
    ;;
esac

expected_jna_suffix="/lib/jna/$product_architecture"
case "$jna_path" in
  *"$expected_jna_suffix") ;;
  *)
    echo "Selected JNA path does not match $product_os/$product_architecture: $jna_path" >&2
    exit 2
    ;;
esac

if [[ ! -d "$jna_path" ]]; then
  echo "Selected JNA directory is absent: $jna_path" >&2
  exit 2
fi

jna_option="$jna_prefix$jna_path"
if [[ "$jna_option" =~ [[:space:]] ]]; then
  if [[ "$jna_option" == *\"* || "$jna_option" == *\\* ]]; then
    echo "Selected JNA path cannot be quoted safely for _JAVA_OPTIONS: $jna_path" >&2
    exit 2
  fi
  jna_option="\"$jna_option\""
fi

apple_options="$(printf '%s' "$selection" | jq -er '.apple | join(" ")')"
export METALLURGY_IDEA_JAVA_OPTIONS="$jna_option $apple_options"

exec "$@"

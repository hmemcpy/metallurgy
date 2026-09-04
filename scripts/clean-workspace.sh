#!/usr/bin/env bash
# Delete regenerable build artifacts under the repository's target/ directory.
# Test-lane evidence survives only for the packet prefixes passed with --keep;
# without --keep every entry under target/ is removed, including all evidence.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: clean-workspace.sh [--keep <prefix>]... [--evidence-dir <name>] [--dry-run]

Deletes every entry under <repo>/target. Inside the evidence directory, a
subtree survives only when its directory name starts with one of the --keep
prefixes; structural lane directories left empty by the prune are removed.

  --keep <prefix>       Retain evidence subtrees whose name starts with
                        <prefix>. Repeatable. Packet evidence such as
                        p125m-syntax matches the packet prefix p125m.
  --evidence-dir <name> Evidence directory name relative to target/
                        (default: test-evidence).
  --dry-run             Print the plan without deleting anything.
EOF
}

dry_run=0
evidence_name=test-evidence
keep_prefixes=()

while [ $# -gt 0 ]; do
  case $1 in
    --keep)
      if [ $# -lt 2 ] || [ -z "$2" ]; then
        echo "error: --keep requires a non-empty prefix" >&2
        exit 2
      fi
      keep_prefixes+=("$2")
      shift 2
      ;;
    --evidence-dir)
      if [ $# -lt 2 ] || [ -z "$2" ]; then
        echo "error: --evidence-dir requires a non-empty name" >&2
        exit 2
      fi
      evidence_name=$2
      shift 2
      ;;
    --dry-run)
      dry_run=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "error: unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
target_dir="$repo_root/target"
evidence_dir="$target_dir/$evidence_name"

if [ ! -d "$target_dir" ]; then
  echo "nothing to clean: $target_dir does not exist"
  exit 0
fi
if [ -L "$target_dir" ]; then
  echo "refusing to clean: $target_dir is a symlink" >&2
  exit 1
fi
case $evidence_name in
  /* | . | .. | *//*)
    echo "refusing to clean: evidence directory must be a relative name inside target/" >&2
    exit 1
    ;;
esac
IFS='/' read -r -a evidence_parts <<< "$evidence_name"
for part in ${evidence_parts[@]+"${evidence_parts[@]}"}; do
  case $part in
    '' | ..*)
      echo "refusing to clean: evidence directory must be a relative name inside target/" >&2
      exit 1
      ;;
  esac
done
evidence_dir="$target_dir/$evidence_name"
if [ -e "$evidence_dir" ] && [ ! -d "$evidence_dir" ]; then
  echo "refusing to clean: $evidence_dir is not a directory" >&2
  exit 1
fi
if [ -L "$evidence_dir" ]; then
  echo "refusing to clean: $evidence_dir is a symlink" >&2
  exit 1
fi

matches_keep() {
  local name=$1 prefix
  for prefix in ${keep_prefixes[@]+"${keep_prefixes[@]}"}; do
    if [[ $name == "$prefix"* ]]; then
      return 0
    fi
  done
  return 1
}

delete_path() {
  local path=$1 display=${1#"$repo_root"/}
  if [ "$dry_run" -eq 1 ]; then
    echo "would delete $display"
  else
    chmod -R u+w "$path" 2>/dev/null || true
    rm -rf -- "$path"
    echo "deleted $display"
  fi
}

# Keep matching subtrees whole; recurse into non-matching directories and
# drop the files and empty shells they leave behind.
prune_evidence() {
  local dir=$1 child
  while IFS= read -r -d '' child; do
    if matches_keep "${child##*/}"; then
      echo "kept ${child#"$repo_root"/}"
    elif [ -d "$child" ]; then
      prune_evidence "$child"
      if [ "$dry_run" -eq 0 ]; then
        rmdir -- "$child" 2>/dev/null || true
      fi
    else
      delete_path "$child"
    fi
  done < <(find "$dir" -mindepth 1 -maxdepth 1 -print0 | sort -z)
}

disk_avail_k() {
  df -k "$1" 2>/dev/null | awk 'NR == 2 { print $4 }'
}

before_k=$(disk_avail_k "$target_dir")

while IFS= read -r -d '' entry; do
  if [ "${entry##*/}" != "$evidence_name" ]; then
    delete_path "$entry"
  fi
done < <(find "$target_dir" -mindepth 1 -maxdepth 1 -print0 | sort -z)

if [ -d "$evidence_dir" ]; then
  if [ ${#keep_prefixes[@]} -eq 0 ]; then
    delete_path "$evidence_dir"
  else
    prune_evidence "$evidence_dir"
  fi
fi

if [ "$dry_run" -eq 0 ]; then
  after_k=$(disk_avail_k "$target_dir")
  if [ -n "$before_k" ] && [ -n "$after_k" ]; then
    awk -v b="$before_k" -v a="$after_k" \
      'BEGIN { printf "freed %.1f GiB\n", (a - b) / (1024 * 1024) }'
  fi
fi

#!/usr/bin/env python3
"""Run a generated intellij-scala Scala 3 test manifest in hard-timeout shards."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path
from typing import Any


SUMMARY = re.compile(r"Passed: Total (?P<total>\d+), Failed (?P<failed>\d+), Errors (?P<errors>\d+)")
FAILED_SUMMARY = re.compile(r"Failed: Total (?P<total>\d+), Failed (?P<failed>\d+), Errors (?P<errors>\d+)")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--manifest", type=Path, default=Path("target/intellij-scala3-tests.json"))
    parser.add_argument("--report", type=Path, default=Path("target/intellij-scala3-test-report.json"))
    parser.add_argument("--shards", type=int, default=16)
    parser.add_argument("--shard", type=int, action="append")
    parser.add_argument("--timeout-seconds", type=int, default=600)
    parser.add_argument(
        "--test-system-dir",
        type=Path,
        default=Path.home() / ".ScalaPluginIC" / "test-system",
    )
    return parser.parse_args()


def load_manifest(path: Path) -> dict[str, Any]:
    result = json.loads(path.read_text(encoding="utf-8"))
    if result.get("schemaVersion") != 1 or not isinstance(result.get("classes"), list):
        raise ValueError("test manifest must use schemaVersion 1 and contain classes")
    return result


def shard_for(name: str, count: int) -> int:
    digest = hashlib.sha256(name.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], byteorder="big") % count


def partition(classes: list[str], count: int) -> list[list[str]]:
    if count <= 0:
        raise ValueError("shard count must be positive")
    result: list[list[str]] = [[] for _ in range(count)]
    for name in sorted(classes):
        result[shard_for(name, count)].append(name)
    return result


def summaries(text: str) -> dict[str, int]:
    totals = {"total": 0, "failed": 0, "errors": 0}
    for pattern in (SUMMARY, FAILED_SUMMARY):
        for match in pattern.finditer(text):
            for name in totals:
                totals[name] += int(match.group(name))
    return totals


def git_output(checkout: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=checkout,
        check=True,
        capture_output=True,
        text=True,
    ).stdout


def reset_test_index(test_system: Path) -> Path:
    index = test_system.expanduser().resolve() / "index"
    shutil.rmtree(index, ignore_errors=True)
    return index


def main() -> int:
    args = arguments()
    if args.timeout_seconds <= 0:
        raise SystemExit("--timeout-seconds must be positive")
    timeout = shutil.which("gtimeout")
    sbt = os.environ.get("SBT") or shutil.which("sbt")
    java_home = os.environ.get("GRADUATION_JAVA_HOME") or os.environ.get("JAVA_HOME")
    if timeout is None or sbt is None:
        raise SystemExit("gtimeout and sbt are required")
    if not java_home:
        raise SystemExit("set GRADUATION_JAVA_HOME or JAVA_HOME")

    manifest = load_manifest(args.manifest)
    classes = [entry["name"] for entry in manifest["classes"]]
    shards = partition(classes, args.shards)
    requested = sorted(set(args.shard if args.shard is not None else range(args.shards)))
    if any(index < 0 or index >= args.shards for index in requested):
        raise SystemExit(f"shard indices must be between 0 and {args.shards - 1}")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    log_directory = args.report.parent / f"{args.report.stem}-logs"
    log_directory.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ)
    environment["JAVA_HOME"] = java_home
    environment["PATH"] = f"{Path(java_home) / 'bin'}:{environment['PATH']}"
    revision = git_output(args.checkout, "rev-parse", "HEAD").strip()
    build_diff = git_output(args.checkout, "diff", "--", "build.sbt")
    records: list[dict[str, object]] = []

    for index in requested:
        selected = shards[index]
        log = log_directory / f"shard-{index:02d}.log"
        reset_index = reset_test_index(args.test_system_dir)
        command = [
            timeout,
            "--kill-after=10s",
            f"{args.timeout_seconds}s",
            sbt,
            "-batch",
            "-no-colors",
            f"testOnly {' '.join(selected)}",
        ]
        started = time.monotonic()
        with log.open("w", encoding="utf-8") as output:
            completed = subprocess.run(
                command,
                cwd=args.checkout,
                env=environment,
                stdout=output,
                stderr=subprocess.STDOUT,
                text=True,
            )
        duration = round(time.monotonic() - started, 3)
        output_text = log.read_text(encoding="utf-8", errors="replace")
        counts = summaries(output_text)
        status = (
            "passed"
            if completed.returncode == 0 and counts["total"] > 0 and counts["failed"] == 0 and counts["errors"] == 0
            else "timeout"
            if completed.returncode == 124
            else "failed"
        )
        record = {
            "shard": index,
            "classCount": len(selected),
            "classes": selected,
            "durationSeconds": duration,
            "exitCode": completed.returncode,
            "status": status,
            "testCounts": counts,
            "log": str(log),
            "resetIndex": str(reset_index),
        }
        records.append(record)
        print(
            f"[{status}] shard {index}/{args.shards - 1}: "
            f"{len(selected)} classes, {counts['total']} tests, {duration}s"
        )

    report = {
        "schemaVersion": 1,
        "checkout": str(args.checkout.resolve()),
        "revision": revision,
        "manifestRevision": manifest["revision"],
        "buildSbtDiffSha256": hashlib.sha256(build_diff.encode("utf-8")).hexdigest(),
        "shardCount": args.shards,
        "timeoutSeconds": args.timeout_seconds,
        "requestedShards": requested,
        "classCount": len(classes),
        "shards": records,
    }
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0 if records and all(record["status"] == "passed" for record in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())

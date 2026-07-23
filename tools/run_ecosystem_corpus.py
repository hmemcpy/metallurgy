#!/usr/bin/env python3
"""Compile immutable Scala 3 ecosystem revisions under hard process-tree timeouts."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any


REQUIRED_PROJECT_FIELDS = {
    "name",
    "repository",
    "revision",
    "scalaVersions",
    "sbtCommands",
    "timeoutSeconds",
}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, default=Path("corpus/ecosystem.json"))
    parser.add_argument("--project", action="append", default=[])
    parser.add_argument("--report", type=Path, default=Path("target/ecosystem-corpus-report.json"))
    parser.add_argument("--keep-workspace", action="store_true")
    return parser.parse_args()


def required_executable(name: str) -> str:
    executable = shutil.which(name)
    if executable is None:
        raise SystemExit(f"required executable is missing: {name}")
    return executable


def source_count(root: Path) -> tuple[int, int]:
    sources = list(root.rglob("*.scala"))
    lines = 0
    for source in sources:
        try:
            lines += sum(1 for _ in source.open("r", encoding="utf-8", errors="replace"))
        except OSError:
            pass
    return len(sources), lines


def load_manifest(path: Path) -> list[dict[str, Any]]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or not isinstance(manifest.get("projects"), list):
        raise ValueError("ecosystem manifest must use schemaVersion 1 and contain a projects array")

    projects = manifest["projects"]
    names: set[str] = set()
    for project in projects:
        missing = REQUIRED_PROJECT_FIELDS - project.keys()
        if missing:
            raise ValueError(f"project is missing fields: {', '.join(sorted(missing))}")
        name = project["name"]
        if not isinstance(name, str) or not name or name in names:
            raise ValueError(f"project name must be nonempty and unique: {name!r}")
        names.add(name)
        revision = project["revision"]
        if not isinstance(revision, str) or len(revision) != 40 or any(ch not in "0123456789abcdef" for ch in revision):
            raise ValueError(f"{name}: revision must be a lowercase, full-length Git commit SHA")
        versions = project["scalaVersions"]
        if not isinstance(versions, list) or not versions or not all(isinstance(version, str) for version in versions):
            raise ValueError(f"{name}: scalaVersions must be a nonempty string array")
        commands = project["sbtCommands"]
        if not isinstance(commands, list) or not commands or not all(isinstance(command, str) for command in commands):
            raise ValueError(f"{name}: sbtCommands must be a nonempty string array")
        if not isinstance(project["timeoutSeconds"], int) or project["timeoutSeconds"] <= 0:
            raise ValueError(f"{name}: timeoutSeconds must be positive")
    return projects


def run_logged(command: list[str], cwd: Path, log: Path, environment: dict[str, str]) -> int:
    with log.open("wb") as output:
        completed = subprocess.run(command, cwd=cwd, env=environment, stdout=output, stderr=subprocess.STDOUT)
    return completed.returncode


def main() -> int:
    args = arguments()
    projects = load_manifest(args.manifest)
    requested = set(args.project)
    if requested:
        known = {project["name"] for project in projects}
        unknown = requested - known
        if unknown:
            raise SystemExit(f"unknown corpus project(s): {', '.join(sorted(unknown))}")
        projects = [project for project in projects if project["name"] in requested]

    timeout = required_executable("gtimeout")
    git = required_executable("git")
    sbt = os.environ.get("SBT", required_executable("sbt"))
    java_home = os.environ.get("CORPUS_JAVA_HOME") or os.environ.get("JAVA_HOME")
    if not java_home:
        raise SystemExit("set CORPUS_JAVA_HOME or JAVA_HOME")

    workspace = Path(tempfile.mkdtemp(prefix="metallurgy-ecosystem-"))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    log_directory = args.report.parent / f"{args.report.stem}-logs"
    log_directory.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, object]] = []
    environment = dict(os.environ)
    environment["JAVA_HOME"] = java_home
    environment["PATH"] = f"{Path(java_home) / 'bin'}:{environment['PATH']}"

    try:
        for project in projects:
            name = project["name"]
            revision = project["revision"]
            project_root = workspace / name
            checkout_log = log_directory / f"{name}-checkout.log"
            compile_log = log_directory / f"{name}-compile.log"
            started = time.monotonic()
            project_root.mkdir()
            subprocess.run([git, "init", "--quiet"], cwd=project_root, check=True)
            subprocess.run(
                [git, "remote", "add", "origin", f"https://github.com/{project['repository']}.git"],
                cwd=project_root,
                check=True,
            )
            checkout_code = run_logged(
                [timeout, "--kill-after=5s", "180s", git, "fetch", "--depth=1", "origin", revision],
                project_root,
                checkout_log,
                environment,
            )
            if checkout_code == 0:
                checkout_code = run_logged(
                    [git, "checkout", "--detach", "--quiet", "FETCH_HEAD"],
                    project_root,
                    checkout_log,
                    environment,
                )
            if checkout_code != 0:
                records.append(
                    {"name": name, "revision": revision, "status": "checkout-failed", "exitCode": checkout_code}
                )
                continue

            head = subprocess.run(
                [git, "rev-parse", "HEAD"],
                cwd=project_root,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            if head != revision:
                records.append(
                    {"name": name, "revision": revision, "status": "revision-mismatch", "actualRevision": head}
                )
                continue

            files, lines = source_count(project_root)
            compile_code = run_logged(
                [
                    timeout,
                    "--kill-after=10s",
                    f"{project['timeoutSeconds']}s",
                    sbt,
                    "-batch",
                    "-no-colors",
                    *project["sbtCommands"],
                ],
                project_root,
                compile_log,
                environment,
            )
            elapsed = round(time.monotonic() - started, 3)
            records.append(
                {
                    "name": name,
                    "repository": project["repository"],
                    "revision": revision,
                    "scalaVersions": project["scalaVersions"],
                    "commands": project["sbtCommands"],
                    "scalaSourceFiles": files,
                    "scalaSourceLines": lines,
                    "durationSeconds": elapsed,
                    "exitCode": compile_code,
                    "status": "passed" if compile_code == 0 else "compile-failed",
                    "compileLog": str(compile_log),
                }
            )
            print(f"[{records[-1]['status']}] {name}: {files} Scala files, {lines} lines, {elapsed}s")
    finally:
        report = {
            "schemaVersion": 1,
            "manifest": str(args.manifest),
            "javaHome": java_home,
            "workspace": str(workspace),
            "projects": records,
        }
        args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        if not args.keep_workspace:
            shutil.rmtree(workspace)

    return 0 if records and all(record["status"] == "passed" for record in records) else 1


if __name__ == "__main__":
    raise SystemExit(main())

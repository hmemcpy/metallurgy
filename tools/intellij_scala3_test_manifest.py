#!/usr/bin/env python3
"""Derive the Scala-3-focused test class manifest from an intellij-scala checkout."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


SCALA3_MARKER = re.compile(
    r"""
    TestScalaVersion\.Scala_3
    |LatestScalaVersions\.Scala_3
    |ScalaVersion\.Latest\.Scala_3
    |ScalaLanguageLevel\.Scala_3
    |(?:is|has)Scala3
    |Scala3
    |scala_3
    """,
    re.VERBOSE | re.IGNORECASE,
)
PACKAGE = re.compile(r"(?m)^\s*package\s+([A-Za-z_][\w.]*)")
CLASS = re.compile(
    r"""(?mx)
    ^\s*
    (?P<prefix>(?:(?:final|sealed|abstract|private|protected)\s+)*)
    class\s+
    (?P<name>[A-Za-z_]\w*)
    (?:\s*\[[^\]]*])?
    \s+extends\s+
    (?P<base>[A-Za-z_][\w.]*)
    """
)
VERSION_CONTRACT = re.compile(
    r"""
    @RunWithScalaVersions
    |supportedIn
    |defaultVersion
    |injectedScalaVersion
    |scalaVersion
    """,
    re.VERBOSE,
)


@dataclass(frozen=True)
class TestClass:
    fqcn: str
    simple_name: str
    base_name: str
    source: str
    concrete: bool
    directly_scala3: bool


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def is_test_source(path: Path) -> bool:
    parts = path.parts
    return (
        path.suffix in {".scala", ".java"}
        and "target" not in parts
        and "testdata" not in parts
        and "test" in parts
    )


def package_at(text: str, position: int) -> str | None:
    components: list[str] = []
    for match in PACKAGE.finditer(text, 0, position):
        name = match.group(1)
        if "." in name and (
            name.startswith("com.")
            or name.startswith("org.")
            or name.startswith("scala.")
        ):
            components = [name]
        else:
            components.append(name)
    return ".".join(components) if components else None


def classes_in(checkout: Path) -> list[TestClass]:
    result: list[TestClass] = []
    for path in sorted(checkout.rglob("*")):
        relative = path.relative_to(checkout)
        if not path.is_file() or not is_test_source(relative):
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        path_marker = bool(SCALA3_MARKER.search(relative.as_posix()))
        matches = list(CLASS.finditer(text))
        for index, match in enumerate(matches):
            package = package_at(text, match.start())
            if package is None:
                continue
            name = match.group("name")
            if "Test" not in name and "Suite" not in name:
                continue
            prefix = match.group("prefix")
            base = match.group("base").rsplit(".", 1)[-1]
            next_start = matches[index + 1].start() if index + 1 < len(matches) else len(text)
            local_text = text[match.start() : next_start]
            annotation_text = text[max(0, match.start() - 500) : match.start()]
            has_local_version_contract = bool(
                SCALA3_MARKER.search(local_text)
                and VERSION_CONTRACT.search(local_text)
                or re.search(
                    r"@RunWithScalaVersions[\s\S]{0,400}Scala_3",
                    annotation_text,
                )
            )
            result.append(
                TestClass(
                    fqcn=f"{package}.{name}",
                    simple_name=name,
                    base_name=base,
                    source=relative.as_posix(),
                    concrete="abstract" not in prefix and "sealed" not in prefix,
                    directly_scala3=(
                        path_marker
                        or bool(SCALA3_MARKER.search(name))
                        or bool(SCALA3_MARKER.search(base))
                        or has_local_version_contract
                    ),
                )
            )
    return result


def scala3_classes(checkout: Path) -> list[TestClass]:
    classes = classes_in(checkout)
    marked_names = {test.simple_name for test in classes if test.directly_scala3}
    changed = True
    while changed:
        inherited = {test.simple_name for test in classes if test.base_name in marked_names}
        expanded = marked_names | inherited
        changed = expanded != marked_names
        marked_names = expanded
    return sorted(
        (test for test in classes if test.concrete and test.simple_name in marked_names),
        key=lambda test: test.fqcn,
    )


def revision(checkout: Path) -> str:
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=checkout,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def manifest(checkout: Path) -> dict[str, object]:
    tests = scala3_classes(checkout)
    return {
        "schemaVersion": 1,
        "checkout": str(checkout.resolve()),
        "revision": revision(checkout),
        "selection": {
            "testSourceOnly": True,
            "scala3Markers": SCALA3_MARKER.pattern.splitlines(),
            "inheritsMarkedBase": True,
            "mixedVersionFilesMayAddSuperset": True,
        },
        "classCount": len(tests),
        "classes": [
            {
                "name": test.fqcn,
                "source": test.source,
                "direct": test.directly_scala3,
            }
            for test in tests
        ],
    }


def main() -> int:
    args = arguments()
    result = manifest(args.checkout)
    rendered = json.dumps(result, indent=2) + "\n"
    if args.output is None:
        print(rendered, end="")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
        print(f"Wrote {result['classCount']} Scala 3-focused test classes to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

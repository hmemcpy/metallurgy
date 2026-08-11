#!/usr/bin/env python3

import argparse
import json
import os
import re
import stat
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path


FQN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+")
TEST_NAME = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
WILDCARDS = frozenset("*?[]")


@dataclass(frozen=True)
class LanePair:
    name: str
    suites: Path
    invocations: Path


class Problems:
    def __init__(self) -> None:
        self.items: list[str] = []

    def add(self, lane: str, problem: str, expected: str, actual: str) -> None:
        self.items.append(
            f"lane {lane}: {problem}\n  expected {expected}\n  actual   {actual}"
        )

    def section(self, lane: str, title: str, rows: list[str]) -> None:
        if rows:
            self.items.append(f"lane {lane}: {title}\n" + "\n".join(f"  {row}" for row in rows))

    def print(self) -> None:
        for item in self.items:
            print(item, file=sys.stderr)


def repository_root() -> Path:
    return Path(__file__).resolve().parent.parent


def lane_name(path: Path) -> str:
    suffix = ".invocations.txt"
    return path.name[: -len(suffix)] if path.name.endswith(suffix) else path.stem


def selected_path(root: Path, value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = root / path
        if not path.exists() and len(Path(value).parts) == 1:
            path = root / "test-lanes" / value
    if path.name.endswith(".invocations.txt"):
        path = path.with_name(path.name.removesuffix(".invocations.txt") + ".txt")
    return path.resolve()


def inventory(root: Path, selected: str | None, problems: Problems) -> list[LanePair]:
    if selected is not None:
        suite_path = selected_path(root, selected)
        name = lane_name(suite_path)
        invocation_path = suite_path.with_name(f"{name}.invocations.txt")
        pairs = [LanePair(name, suite_path, invocation_path)]
    else:
        lane_directory = root / "test-lanes"
        suite_names = {
            path.stem
            for path in lane_directory.glob("*.txt")
            if not path.name.endswith(".invocations.txt")
        }
        invocation_names = {
            lane_name(path) for path in lane_directory.glob("*.invocations.txt")
        }
        names = sorted(suite_names | invocation_names)
        pairs = [
            LanePair(
                name,
                lane_directory / f"{name}.txt",
                lane_directory / f"{name}.invocations.txt",
            )
            for name in names
        ]

    if not pairs:
        problems.add(
            "<all>",
            "no test lanes found",
            "lane pair at index 1",
            "0 lane pairs; no actual index",
        )
        return []

    complete: list[LanePair] = []
    for pair_index, pair in enumerate(pairs, 1):
        missing = [
            str(path) for path in (pair.suites, pair.invocations) if not path.is_file()
        ]
        if missing:
            problems.section(
                pair.name,
                "missing lane pair files",
                [
                    f"expected file at pair index {pair_index}: {path}; actual: missing at pair index {pair_index}"
                    for path in missing
                ],
            )
        else:
            complete.append(pair)
    return complete


def rows(path: Path, lane: str, kind: str, problems: Problems) -> list[str] | None:
    data = path.read_bytes()
    if not data:
        problems.add(lane, f"empty {kind} map", "at least one row at index 1", "0 rows")
        return None
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as error:
        problems.add(
            lane,
            f"{kind} map is not UTF-8",
            f"valid UTF-8 at byte index {error.start}",
            f"invalid byte at index {error.start}",
        )
        return None
    if "\r" in text:
        index = text.index("\r")
        problems.add(
            lane,
            f"{kind} map does not use LF line endings",
            f"LF at character index {index}",
            f"CR at character index {index}",
        )
        return None
    if not text.endswith("\n"):
        actual_rows = text.split("\n")
        problems.add(
            lane,
            f"{kind} map has no final LF",
            f"LF after row index {len(actual_rows)}",
            f"end of file after row index {len(actual_rows)}",
        )
        return None
    result = text[:-1].split("\n")
    blank = [index for index, row in enumerate(result, 1) if not row]
    if blank:
        problems.section(
            lane,
            f"blank {kind} rows",
            [f"expected non-blank row at index {index}; actual blank row" for index in blank],
        )
        return None
    return result


def report_duplicates(lane: str, kind: str, values: list[str], problems: Problems) -> bool:
    indexes: dict[str, list[int]] = {}
    for index, value in enumerate(values, 1):
        indexes.setdefault(value, []).append(index)
    duplicates = [
        f"duplicate row {value!r}; actual indexes {', '.join(map(str, found))}; expected one index"
        for value, found in indexes.items()
        if len(found) > 1
    ]
    problems.section(lane, f"duplicate {kind} rows", duplicates)
    return bool(duplicates)


def report_order(lane: str, kind: str, values: list[str], problems: Problems) -> bool:
    expected = sorted(values)
    if values == expected:
        return False
    index = next(index for index, pair in enumerate(zip(expected, values), 1) if pair[0] != pair[1])
    problems.add(
        lane,
        f"unsorted {kind} rows",
        f"row index {index}: {expected[index - 1]!r}",
        f"row index {index}: {values[index - 1]!r}",
    )
    return True


def validate_suite_rows(lane: str, values: list[str], problems: Problems) -> bool:
    valid = True
    for index, value in enumerate(values, 1):
        if any(character in value for character in WILDCARDS):
            problems.add(
                lane,
                "wildcard suite selector",
                f"literal suite FQN at row index {index}",
                f"row index {index}: {value!r}",
            )
            valid = False
        elif FQN.fullmatch(value) is None:
            problems.add(
                lane,
                "malformed suite FQN",
                f"dot-separated suite FQN at row index {index}",
                f"row index {index}: {value!r}",
            )
            valid = False
    if report_duplicates(lane, "suite-view", values, problems):
        valid = False
    return valid


def validate_invocation_rows(
    lane: str, values: list[str], problems: Problems
) -> tuple[list[str], list[tuple[str, str]]] | None:
    valid = True
    parsed: list[tuple[str, str]] = []
    for index, value in enumerate(values, 1):
        if any(character in value for character in WILDCARDS):
            problems.add(
                lane,
                "wildcard invocation selector",
                f"literal suite and test identity at row index {index}",
                f"row index {index}: {value!r}",
            )
            valid = False
            continue
        parts = value.split("\t")
        if len(parts) != 2:
            problems.add(
                lane,
                "malformed invocation identity",
                f"suite-FQN<TAB>test-name at row index {index}",
                f"row index {index}: {value!r}",
            )
            valid = False
            continue
        suite, test = parts
        if FQN.fullmatch(suite) is None:
            problems.add(
                lane,
                "malformed invocation suite FQN",
                f"dot-separated suite FQN at row index {index}",
                f"row index {index}: {suite!r}",
            )
            valid = False
        if TEST_NAME.fullmatch(test) is None:
            problems.add(
                lane,
                "malformed test identity",
                f"test method name at row index {index}",
                f"row index {index}: {test!r}",
            )
            valid = False
        parsed.append((suite, test))

    if report_duplicates(lane, "invocation", values, problems):
        valid = False
    if report_order(lane, "invocation", values, problems):
        valid = False

    seen: set[str] = set()
    previous: str | None = None
    non_contiguous: list[str] = []
    for index, (suite, _) in enumerate(parsed, 1):
        if suite != previous:
            if suite in seen:
                non_contiguous.append(
                    f"suite {suite!r} resumes at actual row index {index}; expected one contiguous group"
                )
            if previous is not None:
                seen.add(previous)
            previous = suite
    problems.section(lane, "non-contiguous suite groups", non_contiguous)
    if non_contiguous:
        valid = False

    if not valid:
        return None
    suites: list[str] = []
    for suite, _ in parsed:
        if not suites or suites[-1] != suite:
            suites.append(suite)
    return suites, parsed


def compare_view(lane: str, expected: list[str], actual: list[str], problems: Problems) -> None:
    expected_set = set(expected)
    actual_set = set(actual)
    problems.section(
        lane,
        "missing suite-view rows",
        [
            f"expected row index {index}: {value!r}; actual: missing"
            for index, value in enumerate(expected, 1)
            if value not in actual_set
        ],
    )
    problems.section(
        lane,
        "extra suite-view rows",
        [
            f"actual row index {index}: {value!r}; expected: absent"
            for index, value in enumerate(actual, 1)
            if value not in expected_set
        ],
    )
    if len(expected) == len(actual) and expected_set == actual_set and expected != actual:
        problems.section(
            lane,
            "reordered suite-view rows",
            [
                f"index {index}: expected {expected_value!r}; actual {actual_value!r}"
                for index, (expected_value, actual_value) in enumerate(zip(expected, actual), 1)
                if expected_value != actual_value
            ],
        )


def copied_suites(root: Path, problems: Problems) -> list[tuple[str, list[str]]]:
    manifest = root / "upstream-tests" / "intellij-scala.json"
    if not manifest.is_file():
        problems.add(
            "<copied>",
            "copied generated-suite manifest is missing",
            f"file: {manifest}",
            "file: missing",
        )
        return []
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        suites = [
            (
                suite["generated"]["owner"],
                [method["localName"] for method in suite["methods"]],
            )
            for suite in data["suites"]
        ]
    except (KeyError, TypeError, json.JSONDecodeError) as error:
        problems.add(
            "<copied>",
            "copied generated-suite manifest is malformed",
            "generated owner and local method rows",
            f"manifest error: {error}",
        )
        return []
    return suites


def compare_copied(
    root: Path,
    lane_invocations: dict[str, list[tuple[str, str]]],
    problems: Problems,
) -> None:
    for owner, expected_methods in copied_suites(root, problems):
        for lane, invocations in lane_invocations.items():
            actual_methods = [test for suite, test in invocations if suite == owner]
            if not actual_methods:
                continue
            missing = [method for method in expected_methods if method not in actual_methods]
            extra = [method for method in actual_methods if method not in expected_methods]
            problems.section(
                lane,
                f"missing copied generated-suite invocations for {owner}",
                [
                    f"expected method row index {expected_methods.index(method) + 1}: {method!r}; actual: missing"
                    for method in missing
                ],
            )
            problems.section(
                lane,
                f"extra copied generated-suite invocations for {owner}",
                [
                    f"actual method row index {actual_methods.index(method) + 1}: {method!r}; expected: absent"
                    for method in extra
                ],
            )
            if not missing and not extra and actual_methods != expected_methods:
                problems.section(
                    lane,
                    f"reordered copied generated-suite invocations for {owner}",
                    [
                        f"index {index}: expected {expected!r}; actual {actual!r}"
                        for index, (expected, actual) in enumerate(
                            zip(expected_methods, actual_methods), 1
                        )
                        if expected != actual
                    ],
                )


def load(
    root: Path, selected: str | None, validate_views: bool, problems: Problems
) -> tuple[list[LanePair], dict[str, list[str]], dict[str, list[tuple[str, str]]]]:
    pairs = inventory(root, selected, problems)
    generated: dict[str, list[str]] = {}
    parsed_invocations: dict[str, list[tuple[str, str]]] = {}
    for pair in pairs:
        invocation_rows = rows(pair.invocations, pair.name, "invocation", problems)
        if invocation_rows is None:
            continue
        parsed = validate_invocation_rows(pair.name, invocation_rows, problems)
        if parsed is None:
            continue
        expected_suites, invocations = parsed
        generated[pair.name] = expected_suites
        parsed_invocations[pair.name] = invocations
        if validate_views:
            suite_rows = rows(pair.suites, pair.name, "suite-view", problems)
            if suite_rows is None:
                continue
            if validate_suite_rows(pair.name, suite_rows, problems):
                compare_view(pair.name, expected_suites, suite_rows, problems)
    compare_copied(root, parsed_invocations, problems)
    return pairs, generated, parsed_invocations


def atomic_write(path: Path, values: list[str]) -> None:
    content = "".join(f"{value}\n" for value in values).encode("utf-8")
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(content)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check or generate deterministic test-lane suite views."
    )
    parser.add_argument("mode", choices=("check", "write"))
    parser.add_argument("--lane", help="check or write only this lane suite view")
    parser.add_argument(
        "--test-root",
        help="repository-shaped temporary root used only by mutation tests",
    )
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()
    root = Path(arguments.test_root).resolve() if arguments.test_root else repository_root()
    problems = Problems()
    pairs, generated, _ = load(
        root, arguments.lane, arguments.mode == "check", problems
    )
    if problems.items:
        problems.print()
        return 1
    if arguments.mode == "write":
        for pair in pairs:
            atomic_write(pair.suites, generated[pair.name])
        verification = Problems()
        load(root, arguments.lane, True, verification)
        if verification.items:
            verification.print()
            return 1
        print(f"wrote {len(pairs)} deterministic lane suite view(s)")
    else:
        print(f"checked {len(pairs)} deterministic lane mapping(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

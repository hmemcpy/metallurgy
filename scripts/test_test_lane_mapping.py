#!/usr/bin/env python3

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("test_lane_mapping.py")
A = "com.example.ATest"
B = "com.example.BTest"


class TestLaneMappingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="metallurgy-lane-mapping-")
        self.root = Path(self.temporary.name)
        (self.root / "test-lanes").mkdir()
        (self.root / "upstream-tests").mkdir()
        self.write_manifest([])
        self.write_lane("alpha", [A, B], [(A, "testA"), (A, "testB"), (B, "testC")])

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_manifest(self, suites: list[dict[str, object]]) -> None:
        (self.root / "upstream-tests" / "intellij-scala.json").write_text(
            json.dumps({"suites": suites}) + "\n", encoding="utf-8", newline="\n"
        )

    def write_lane(
        self,
        name: str,
        suites: list[str],
        invocations: list[tuple[str, str]],
    ) -> None:
        lane_directory = self.root / "test-lanes"
        (lane_directory / f"{name}.txt").write_text(
            "".join(f"{suite}\n" for suite in suites), encoding="utf-8", newline="\n"
        )
        (lane_directory / f"{name}.invocations.txt").write_text(
            "".join(f"{suite}\t{test}\n" for suite, test in invocations),
            encoding="utf-8",
            newline="\n",
        )

    def run_mapping(
        self, mode: str = "check", cwd: Path | None = None
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), mode, "--test-root", str(self.root)],
            cwd=cwd,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def assert_rejected(self, message: str) -> subprocess.CompletedProcess[str]:
        result = self.run_mapping()
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("lane ", result.stderr)
        self.assertIn(message, result.stderr)
        self.assertIn("index", result.stderr)
        return result

    def test_missing_lane_pair_is_rejected(self) -> None:
        (self.root / "test-lanes" / "alpha.txt").unlink()
        self.assert_rejected("missing lane pair files")

    def test_missing_suite_view_row_has_plain_section(self) -> None:
        self.write_lane("alpha", [A], [(A, "testA"), (A, "testB"), (B, "testC")])
        self.assert_rejected("missing suite-view rows")

    def test_extra_suite_view_row_has_plain_section(self) -> None:
        self.write_lane(
            "alpha",
            [A, B, "com.example.CTest"],
            [(A, "testA"), (A, "testB"), (B, "testC")],
        )
        self.assert_rejected("extra suite-view rows")

    def test_reordered_suite_view_rows_have_plain_section(self) -> None:
        self.write_lane("alpha", [B, A], [(A, "testA"), (A, "testB"), (B, "testC")])
        self.assert_rejected("reordered suite-view rows")

    def test_duplicate_invocation_rows_have_plain_section(self) -> None:
        self.write_lane(
            "alpha",
            [A, B],
            [(A, "testA"), (A, "testA"), (A, "testB"), (B, "testC")],
        )
        self.assert_rejected("duplicate invocation rows")

    def test_malformed_fqn_and_test_identity_are_rejected(self) -> None:
        with self.subTest("FQN"):
            self.write_lane("alpha", [A], [("not-a-fqn", "testA")])
            self.assert_rejected("malformed invocation suite FQN")
        with self.subTest("test identity"):
            self.write_lane("alpha", [A], [(A, "not-a-test-identity")])
            self.assert_rejected("malformed test identity")

    def test_wildcards_are_rejected(self) -> None:
        self.write_lane("alpha", [A], [("com.example.*", "testA")])
        self.assert_rejected("wildcard invocation selector")

    def test_empty_suite_and_invocation_maps_are_rejected(self) -> None:
        with self.subTest("invocations"):
            (self.root / "test-lanes" / "alpha.invocations.txt").write_bytes(b"")
            self.assert_rejected("empty invocation map")
        with self.subTest("suite view"):
            self.write_lane("alpha", [], [(A, "testA")])
            self.assert_rejected("empty suite-view map")

    def test_zero_lanes_are_rejected(self) -> None:
        for path in (self.root / "test-lanes").iterdir():
            path.unlink()
        self.assert_rejected("no test lanes found")

    def test_copied_generated_suite_mismatch_is_rejected(self) -> None:
        methods = [f"test{index}" for index in range(1, 6)]
        self.write_manifest(
            [
                {
                    "generated": {"owner": B},
                    "methods": [{"localName": method} for method in methods],
                }
            ]
        )
        self.write_lane("alpha", [B], [(B, method) for method in methods[:-1]])
        self.assert_rejected("missing copied generated-suite invocations")

    def test_non_contiguous_suite_group_is_rejected(self) -> None:
        self.write_lane("alpha", [A, B], [(A, "testA"), (B, "testC"), (A, "testB")])
        self.assert_rejected("non-contiguous suite groups")

    def test_two_writes_are_byte_identical(self) -> None:
        suite_view = self.root / "test-lanes" / "alpha.txt"
        suite_view.write_text(f"{B}\n{A}\n", encoding="utf-8", newline="\n")
        first = self.run_mapping("write")
        self.assertEqual(0, first.returncode, first.stderr)
        first_bytes = suite_view.read_bytes()
        second = self.run_mapping("write")
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertEqual(first_bytes, suite_view.read_bytes())
        self.assertEqual(f"{A}\n{B}\n".encode(), first_bytes)

    def test_unrelated_working_directory_does_not_change_root(self) -> None:
        with tempfile.TemporaryDirectory(prefix="metallurgy-unrelated-") as unrelated:
            result = self.run_mapping(cwd=Path(unrelated))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("checked 1 deterministic lane mapping(s)", result.stdout)


if __name__ == "__main__":
    unittest.main()

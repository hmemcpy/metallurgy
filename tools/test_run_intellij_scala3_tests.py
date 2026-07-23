#!/usr/bin/env python3

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_intellij_scala3_tests.py")
SPEC = importlib.util.spec_from_file_location("run_intellij_scala3_tests", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
RUNNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RUNNER
SPEC.loader.exec_module(RUNNER)


class IntellijScala3TestRunnerTest(unittest.TestCase):
    def test_partition_is_complete_stable_and_disjoint(self) -> None:
        classes = [f"example.Test{index}" for index in range(100)]

        first = RUNNER.partition(classes, 7)
        second = RUNNER.partition(list(reversed(classes)), 7)

        self.assertEqual(first, second)
        self.assertEqual(sorted(classes), sorted(name for shard in first for name in shard))
        self.assertEqual(len(classes), len({name for shard in first for name in shard}))

    def test_summaries_combine_aggregated_project_results(self) -> None:
        output = """
        [info] Passed: Total 12, Failed 0, Errors 0, Passed 12
        [error] Failed: Total 7, Failed 1, Errors 2, Passed 4
        """

        self.assertEqual(
            {"total": 19, "failed": 1, "errors": 2},
            RUNNER.summaries(output),
        )

    def test_reset_test_index_removes_only_the_generated_index(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            test_system = Path(directory)
            index = test_system / "index"
            index.mkdir()
            (index / "stale").write_text("data", encoding="utf-8")
            other = test_system / "compile-server"
            other.mkdir()

            self.assertEqual(index.resolve(), RUNNER.reset_test_index(test_system))
            self.assertFalse(index.exists())
            self.assertTrue(other.exists())


if __name__ == "__main__":
    unittest.main()

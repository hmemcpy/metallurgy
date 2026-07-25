#!/usr/bin/env python3

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("intellij_scala3_test_manifest.py")
SPEC = importlib.util.spec_from_file_location("intellij_scala3_test_manifest", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MANIFEST = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MANIFEST
SPEC.loader.exec_module(MANIFEST)


class IntellijScala3TestManifestTest(unittest.TestCase):
    def test_direct_and_inherited_scala3_tests_are_selected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            test_root = root / "module" / "test" / "example"
            test_root.mkdir(parents=True)
            (test_root / "Base.scala").write_text(
                """package example
                  |import org.jetbrains.plugins.scala.util.runners.TestScalaVersion
                  |abstract class Scala3BaseTest extends Fixture(TestScalaVersion.Scala_3_Latest)
                  |class ConcreteTest extends Scala3BaseTest
                  |""".replace("|", ""),
                encoding="utf-8",
            )
            (test_root / "Scala2.scala").write_text(
                "package example\nclass Scala2OnlyTest extends Fixture\n",
                encoding="utf-8",
            )

            selected = MANIFEST.scala3_classes(root)

            self.assertEqual(["example.ConcreteTest"], [test.fqcn for test in selected])

    def test_manifest_records_revision_and_selection_contract(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            test_root = root / "module" / "test" / "example" / "scala3"
            test_root.mkdir(parents=True)
            (test_root / "FeatureTest.scala").write_text(
                "package example\nfinal class FeatureTest extends Fixture\n",
                encoding="utf-8",
            )

            with patch.object(MANIFEST, "revision", return_value="a" * 40):
                result = MANIFEST.manifest(root)

            self.assertEqual(1, result["classCount"])
            self.assertEqual("example.FeatureTest", result["classes"][0]["name"])

    def test_sequential_package_clauses_form_the_fully_qualified_name(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            test_root = root / "module" / "test" / "example" / "scala3"
            test_root.mkdir(parents=True)
            (test_root / "FeatureTest.scala").write_text(
                """package org.jetbrains.plugins.scala
                  |package debugger.evaluation
                  |final class FeatureTest extends Fixture
                  |""".replace("|", ""),
                encoding="utf-8",
            )

            selected = MANIFEST.scala3_classes(root)

            self.assertEqual(
                ["org.jetbrains.plugins.scala.debugger.evaluation.FeatureTest"],
                [test.fqcn for test in selected],
            )


if __name__ == "__main__":
    unittest.main()

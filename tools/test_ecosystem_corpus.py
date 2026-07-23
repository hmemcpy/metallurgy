#!/usr/bin/env python3

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("run_ecosystem_corpus.py")
SPEC = importlib.util.spec_from_file_location("run_ecosystem_corpus", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
CORPUS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CORPUS)


class EcosystemCorpusManifestTest(unittest.TestCase):
    def test_repository_manifest_is_valid(self) -> None:
        projects = CORPUS.load_manifest(Path("corpus/ecosystem.json"))
        self.assertEqual(
            ["cats", "cats-effect", "zio", "shapeless-3", "tapir", "fs2"],
            [project["name"] for project in projects],
        )
        self.assertEqual(["3.3.8", "3.7.4"], projects[4]["scalaVersions"])

    def test_rejects_floating_revision(self) -> None:
        manifest = {
            "schemaVersion": 1,
            "projects": [
                {
                    "name": "example",
                    "repository": "owner/repository",
                    "revision": "main",
                    "scalaVersions": ["3.3.8"],
                    "sbtCommands": ["compile"],
                    "timeoutSeconds": 60,
                }
            ],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "full-length Git commit SHA"):
                CORPUS.load_manifest(path)


if __name__ == "__main__":
    unittest.main()

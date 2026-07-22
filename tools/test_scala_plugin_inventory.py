from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import scala_plugin_inventory


class ScalaPluginInventoryTest(unittest.TestCase):
    def test_discovers_module_extensions_points_extensions_actions_and_registry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".git" / "refs" / "heads").mkdir(parents=True)
            (root / ".git" / "HEAD").write_text("ref: refs/heads/main\n", encoding="utf-8")
            (root / ".git" / "refs" / "heads" / "main").write_text("abc123\n", encoding="utf-8")
            descriptor = root / "plugin" / "resources" / "META-INF" / "plugin.xml"
            descriptor.parent.mkdir(parents=True)
            descriptor.write_text(
                """<idea-plugin xmlns:xi="http://www.w3.org/2001/XInclude">
  <content><module name="scalaCommunity.uast"/></content>
  <dependencies><module name="intellij.platform.uast"/></dependencies>
  <xi:include href="/META-INF/common.xml"/>
  <extensionPoints><extensionPoint name="resolver" interface="example.Resolver"/></extensionPoints>
  <extensions defaultExtensionNs="com.intellij">
    <projectService serviceImplementation="example.Service"/>
    <registryKey key="scala.experimental" defaultValue="false"/>
  </extensions>
  <actions><action id="Scala.Run" class="example.Run"/></actions>
</idea-plugin>""",
                encoding="utf-8",
            )

            generated = scala_plugin_inventory.inventory(root)

            self.assertEqual("abc123", generated["sourceRevision"])
            self.assertEqual(1, generated["descriptorCount"])
            identities = {(item["kind"], item["name"]) for item in generated["registrations"]}
            self.assertIn(("content-module", "scalaCommunity.uast"), identities)
            self.assertIn(("module-dependency", "intellij.platform.uast"), identities)
            self.assertIn(("include", "/META-INF/common.xml"), identities)
            self.assertIn(("extension-point", "resolver"), identities)
            self.assertIn(("extension", "com.intellij.projectService"), identities)
            self.assertIn(("extension", "com.intellij.registryKey:scala.experimental"), identities)
            self.assertIn(("action", "Scala.Run"), identities)

    def test_check_detects_a_new_upstream_registration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            descriptor = root / "module" / "resources" / "scalaCommunity.sample.xml"
            descriptor.parent.mkdir(parents=True)
            descriptor.write_text(
                '<idea-plugin><extensions defaultExtensionNs="com.intellij">'
                '<projectService serviceImplementation="example.First"/>'
                "</extensions></idea-plugin>",
                encoding="utf-8",
            )
            snapshot = root / "snapshot.json"
            snapshot.write_text(scala_plugin_inventory.encoded(scala_plugin_inventory.inventory(root)), encoding="utf-8")
            self.assertEqual(0, scala_plugin_inventory.command_check(root, snapshot))

            descriptor.write_text(
                '<idea-plugin><extensions defaultExtensionNs="com.intellij">'
                '<projectService serviceImplementation="example.First"/>'
                '<projectService serviceImplementation="example.Second"/>'
                "</extensions></idea-plugin>",
                encoding="utf-8",
            )
            self.assertEqual(1, scala_plugin_inventory.command_check(root, snapshot))


if __name__ == "__main__":
    unittest.main()

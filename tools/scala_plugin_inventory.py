#!/usr/bin/env python3
"""Generate a deterministic inventory from an intellij-scala source checkout."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True, order=True)
class Registration:
    kind: str
    name: str
    source: str
    line: int
    implementation: str = ""


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def descriptor_paths(root: Path) -> list[Path]:
    candidates = set(root.glob("**/resources/META-INF/*.xml"))
    candidates.update(root.glob("**/resources/scalaCommunity.*.xml"))
    return sorted(path for path in candidates if path.is_file())


def source_line(path: Path, tag: str, distinguishing_value: str = "") -> int:
    lines = path.read_text(encoding="iso-8859-1").splitlines()
    if distinguishing_value:
        for index, line in enumerate(lines):
            if distinguishing_value not in line:
                continue
            for candidate in range(index, max(-1, index - 12), -1):
                if f"<{tag}" in lines[candidate]:
                    return candidate + 1
    for index, line in enumerate(lines):
        if f"<{tag}" not in line:
            continue
        window = " ".join(lines[index : index + 12])
        if not distinguishing_value or distinguishing_value in window:
            return index + 1
    return 1


def implementation_of(attributes: dict[str, str]) -> str:
    preferred = (
        "implementation",
        "implementationClass",
        "serviceImplementation",
        "instance",
        "class",
        "provider",
        "factoryClass",
    )
    values = [attributes[key] for key in preferred if attributes.get(key)]
    return ", ".join(dict.fromkeys(values))


def registration_name(namespace: str, tag: str, attributes: dict[str, str]) -> str:
    qualified = f"{namespace}.{tag}" if namespace else tag
    identity = next(
        (
            attributes[key]
            for key in ("qualifiedName", "name", "id", "key", "language", "filetype")
            if attributes.get(key)
        ),
        "",
    )
    return f"{qualified}:{identity}" if identity else qualified


def parse_descriptor(root: Path, path: Path) -> list[Registration]:
    document = ET.parse(path).getroot()
    relative = path.relative_to(root).as_posix()
    registrations: list[Registration] = []

    for element in document.iter():
        tag = local_name(element.tag)
        attributes = {local_name(key): value for key, value in element.attrib.items()}
        if tag == "include" and attributes.get("href"):
            href = attributes["href"]
            registrations.append(Registration("include", href, relative, source_line(path, "xi:include", href)))
        elif tag == "extensionPoint":
            name = attributes.get("qualifiedName") or attributes.get("name") or "<anonymous>"
            registrations.append(
                Registration(
                    "extension-point",
                    name,
                    relative,
                    source_line(path, tag, name),
                    implementation_of(attributes),
                )
            )

    for container in document.iter():
        container_tag = local_name(container.tag)
        if container_tag in {"content", "dependencies"}:
            kind = "content-module" if container_tag == "content" else "module-dependency"
            for module in list(container):
                if local_name(module.tag) != "module" or not module.attrib.get("name"):
                    continue
                name = module.attrib["name"]
                registrations.append(Registration(kind, name, relative, source_line(path, "module", name)))
        elif container_tag == "extensions":
            namespace = container.attrib.get("defaultExtensionNs", "")
            for extension in list(container):
                tag = local_name(extension.tag)
                attributes = {local_name(key): value for key, value in extension.attrib.items()}
                name = registration_name(namespace, tag, attributes)
                implementation = implementation_of(attributes)
                distinguishing = implementation or next(
                    (attributes[key] for key in ("key", "id", "name") if attributes.get(key)),
                    tag,
                )
                registrations.append(
                    Registration(
                        "extension",
                        name,
                        relative,
                        source_line(path, tag, distinguishing),
                        implementation,
                    )
                )
        elif container_tag == "actions":
            for action in container.iter():
                tag = local_name(action.tag)
                if tag not in {"action", "group"}:
                    continue
                attributes = {local_name(key): value for key, value in action.attrib.items()}
                identity = attributes.get("id") or attributes.get("class") or "<anonymous>"
                registrations.append(
                    Registration(
                        tag,
                        identity,
                        relative,
                        source_line(path, tag, identity),
                        attributes.get("class", ""),
                    )
                )

    return registrations


def git_revision(root: Path) -> str:
    dot_git = root / ".git"
    git_dir = dot_git
    if dot_git.is_file():
        marker = dot_git.read_text(encoding="utf-8").strip()
        if marker.startswith("gitdir:"):
            git_dir = (root / marker.removeprefix("gitdir:").strip()).resolve()
    head = git_dir / "HEAD"
    if not head.exists():
        return "unknown"
    value = head.read_text(encoding="utf-8").strip()
    if not value.startswith("ref:"):
        return value
    reference = value.removeprefix("ref:").strip()
    direct = git_dir / reference
    if direct.exists():
        return direct.read_text(encoding="utf-8").strip()
    packed = git_dir / "packed-refs"
    if packed.exists():
        for line in packed.read_text(encoding="utf-8").splitlines():
            if line.endswith(f" {reference}"):
                return line.split(" ", 1)[0]
    return "unknown"


def inventory(root: Path) -> dict[str, object]:
    descriptors = descriptor_paths(root)
    registrations = sorted(
        registration
        for descriptor in descriptors
        for registration in parse_descriptor(root, descriptor)
    )
    counts: dict[str, int] = {}
    for registration in registrations:
        counts[registration.kind] = counts.get(registration.kind, 0) + 1
    return {
        "schemaVersion": 1,
        "sourceRevision": git_revision(root),
        "descriptorCount": len(descriptors),
        "counts": dict(sorted(counts.items())),
        "registrations": [asdict(registration) for registration in registrations],
    }


def encoded(value: dict[str, object]) -> str:
    return json.dumps(value, indent=2, sort_keys=True) + "\n"


def command_generate(root: Path, output: Path | None) -> int:
    rendered = encoded(inventory(root))
    if output is None:
        sys.stdout.write(rendered)
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")
    return 0


def command_check(root: Path, snapshot: Path) -> int:
    actual = encoded(inventory(root))
    expected = snapshot.read_text(encoding="utf-8") if snapshot.exists() else ""
    if actual == expected:
        print(f"Scala-plugin inventory is current: {snapshot}")
        return 0
    print(
        f"Scala-plugin inventory is stale: regenerate {snapshot} from {root}",
        file=sys.stderr,
    )
    return 1


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)
    generate = subcommands.add_parser("generate")
    generate.add_argument("source", type=Path)
    generate.add_argument("--output", type=Path)
    check = subcommands.add_parser("check")
    check.add_argument("source", type=Path)
    check.add_argument("snapshot", type=Path)
    return result


def main(arguments: Iterable[str] | None = None) -> int:
    options = parser().parse_args(arguments)
    source = options.source.resolve()
    if options.command == "generate":
        return command_generate(source, options.output)
    return command_check(source, options.snapshot)


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fail when a visible preference has no implementation-side wiring.

The preference XML is the public contract. A row that can be changed but whose key is
never consumed by app/runtime code is worse than a missing row because it looks like a
working feature. This checker keeps that failure out of releases.
"""

from __future__ import annotations

import os
import re
import sys
from collections import defaultdict
from xml.etree import ElementTree

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
XML_DIR = os.path.join(REPO, "app", "src", "main", "res", "xml")
SRC_DIR = os.path.join(REPO, "app", "src", "main", "java")

ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"

EXCLUDED_SOURCE_FILES = {
    "SettingKeyRegistry.kt",
    "FeatureCatalog.kt",
    "TargetSettingsActivity.kt",
    "TargetSettingsViewModel.kt",
}


def attr(node: ElementTree.Element, name: str) -> str | None:
    return node.get(ANDROID + name) or node.get(APP + name)


def collect_preferences() -> dict[str, list[tuple[str, bool]]]:
    found: dict[str, list[tuple[str, bool]]] = defaultdict(list)
    for filename in sorted(os.listdir(XML_DIR)):
        if not filename.endswith(".xml") or filename == "file_paths.xml":
            continue
        path = os.path.join(XML_DIR, filename)
        root = ElementTree.parse(path).getroot()
        for node in root.iter():
            key = attr(node, "key")
            if not key:
                continue
            enabled = attr(node, "enabled")
            disabled = enabled is not None and enabled.lower() == "false"
            found[key].append((filename, disabled))
    return found


def source_text() -> list[tuple[str, str]]:
    sources: list[tuple[str, str]] = []
    for root, _, files in os.walk(SRC_DIR):
        for filename in files:
            if not filename.endswith((".kt", ".java")):
                continue
            if filename in EXCLUDED_SOURCE_FILES:
                continue
            path = os.path.join(root, filename)
            with open(path, "r", encoding="utf-8") as handle:
                sources.append((os.path.relpath(path, REPO).replace("\\", "/"), handle.read()))
    return sources


def has_literal_reference(key: str, sources: list[tuple[str, str]]) -> list[str]:
    pattern = re.compile(r'["\']' + re.escape(key) + r'["\']')
    return [path for path, text in sources if pattern.search(text)]


def main() -> int:
    preferences = collect_preferences()
    sources = source_text()
    failures: list[str] = []

    duplicates = {key: rows for key, rows in preferences.items() if len(rows) > 1}
    for key, rows in sorted(duplicates.items()):
        locations = ", ".join(name for name, _ in rows)
        failures.append(f"duplicate preference key {key!r}: {locations}")

    wired = 0
    disabled_placeholders = 0
    for key, rows in sorted(preferences.items()):
        refs = has_literal_reference(key, sources)
        if refs:
            wired += 1
            continue
        if all(disabled for _, disabled in rows):
            disabled_placeholders += 1
            continue
        locations = ", ".join(name for name, _ in rows)
        failures.append(f"preference {key!r} has no implementation reference ({locations})")

    if failures:
        print("Preference wiring check failed:", file=sys.stderr)
        for failure in failures:
            print(f"  - {failure}", file=sys.stderr)
        return 1

    print(
        "Preference wiring intact: "
        f"{len(preferences)} unique keys, {wired} wired, "
        f"{disabled_placeholders} intentionally disabled placeholder(s)."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

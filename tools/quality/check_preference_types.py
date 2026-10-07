#!/usr/bin/env python3
"""Fail when a preference is read back through the wrong accessor.

The preference XML is the public contract, and `SharedPreferences` is unforgiving: a key
written with `putBoolean` and read with `getInt` throws `ClassCastException` inside the
hooked WhatsApp process. That is a crash in someone else's app, not a wrong setting, so
the declared element type and the accessor each read site uses have to agree.

The comparison is (element kind from XML) against (accessor kind from source). A key with
no read site is reported by `check_preference_wiring.py` instead; this check is silent
about it because an unread key cannot be read with the wrong accessor.

Exit codes:
    0  every runtime read matches the declared type
    1  at least one read site uses the wrong accessor
    2  the preference XML declares one key as two different types
"""

from __future__ import annotations

import os
import re
import sys
from bisect import bisect_right
from xml.etree import ElementTree

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
XML_DIR = os.path.join(REPO, "app", "src", "main", "res", "xml")
SRC_DIR = os.path.join(REPO, "app", "src", "main", "java")
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"

METHOD_KIND = {
    "getBoolean": "BOOLEAN",
    "getString": "TEXT",
    "getInt": "INT",
    "getFloat": "FLOAT",
    "getStringSet": "SET",
}

ACCESS_RE = re.compile(
    r"""\.(getBoolean|getString|getInt|getFloat|getStringSet)\(\s*["']([^"']+)["']"""
)


def attr(node: ElementTree.Element, name: str) -> str | None:
    return node.get(ANDROID + name) or node.get(APP + name)


def element_kind(tag: str) -> str:
    """The value kind an element name implies, matching the generated registry."""
    simple = tag.rsplit(".", 1)[-1]
    if "SwitchPreference" in simple:
        return "BOOLEAN"
    if simple.endswith("FloatSeekBarPreference"):
        return "FLOAT"
    if simple.endswith("SeekBarPreference") or "ColorPreference" in simple:
        return "INT"
    if simple == "MultiSelectListPreference":
        return "SET"
    return "TEXT"


def preference_kinds() -> dict[str, tuple[str, str]]:
    """key -> (kind, declaring file) for every keyed row in the preference screens."""
    kinds: dict[str, tuple[str, str]] = {}
    for filename in sorted(os.listdir(XML_DIR)):
        if not filename.endswith(".xml") or filename == "file_paths.xml":
            continue
        root = ElementTree.parse(os.path.join(XML_DIR, filename)).getroot()
        for node in root.iter():
            key = attr(node, "key")
            if not key:
                continue
            kind = element_kind(node.tag)
            previous = kinds.get(key)
            if previous is not None and previous[0] != kind:
                raise RuntimeError(
                    f"{key}: conflicting kinds {previous[0]} in {previous[1]} "
                    f"and {kind} in {filename}"
                )
            kinds[key] = (kind, filename)
    return kinds


def runtime_accesses() -> dict[str, list[tuple[str, str, int]]]:
    """key -> [(kind, source path, line)] for every typed accessor call with a literal key."""
    accesses: dict[str, list[tuple[str, str, int]]] = {}
    for root, _, files in os.walk(SRC_DIR):
        for filename in files:
            if not filename.endswith((".kt", ".java")):
                continue
            path = os.path.join(root, filename)
            rel = os.path.relpath(path, REPO).replace(os.sep, "/")
            with open(path, "r", encoding="utf-8") as handle:
                text = handle.read()
            # Newline offsets once, then a binary search per match. Counting "\n" inside
            # the match loop would be correct but quadratic over the whole source tree.
            line_starts = [0]
            for index, char in enumerate(text):
                if char == "\n":
                    line_starts.append(index + 1)
            for match in ACCESS_RE.finditer(text):
                method, key = match.groups()
                line = bisect_right(line_starts, match.start())
                accesses.setdefault(key, []).append((METHOD_KIND[method], rel, line))
    return accesses


def main() -> int:
    declared = preference_kinds()
    accesses = runtime_accesses()
    failures: list[str] = []
    checked = 0
    for key, (expected, xml_file) in sorted(declared.items()):
        for actual, source, line in accesses.get(key, []):
            checked += 1
            if actual != expected:
                failures.append(f"{key}: XML {expected} ({xml_file}) but {source}:{line} reads {actual}")
    if failures:
        print("Preference type contract failed:", file=sys.stderr)
        for failure in failures:
            print("  - " + failure, file=sys.stderr)
        return 1
    print(f"Preference type contract intact: {len(declared)} keys, {checked} runtime reads checked.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except RuntimeError as error:
        print(f"Preference type contract is inconsistent: {error}", file=sys.stderr)
        sys.exit(2)

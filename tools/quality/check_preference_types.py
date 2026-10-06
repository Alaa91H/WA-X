#!/usr/bin/env python3
from __future__ import annotations

import os
import re
import sys
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

ACCESS_RE = re.compile(r"\\.(getBoolean|getString|getInt|getFloat|getStringSet)\\(\\s*[\\\"]([^\\\"]+)[\\\"]")

def attr(node, name):
    return node.get(ANDROID + name) or node.get(APP + name)

def element_kind(tag):
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

def preference_kinds():
    kinds = {}
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
                raise RuntimeError(f"{key}: conflicting kinds {previous[0]} and {kind}")
            kinds[key] = (kind, filename)
    return kinds

def runtime_accesses():
    accesses = {}
    for root, _, files in os.walk(SRC_DIR):
        for filename in files:
            if not filename.endswith((".kt", ".java")):
                continue
            path = os.path.join(root, filename)
            rel = os.path.relpath(path, REPO).replace("\\\\", "/")
            with open(path, "r", encoding="utf-8") as handle:
                text = handle.read()
            for match in ACCESS_RE.finditer(text):
                method, key = match.groups()
                line = text.count("\\n", 0, match.start()) + 1
                accesses.setdefault(key, []).append((METHOD_KIND[method], rel, line))
    return accesses

def main():
    declared = preference_kinds()
    accesses = runtime_accesses()
    failures = []
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
    sys.exit(main())

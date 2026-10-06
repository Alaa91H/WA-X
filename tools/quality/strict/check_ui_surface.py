#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
from collections import defaultdict
from xml.etree import ElementTree

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
XML_DIR = os.path.join(ROOT, "app", "src", "main", "res", "xml")
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"


def attr(node, name):
    return node.get(ANDROID + name) or node.get(APP + name)


def main() -> int:
    issues = []
    keys = defaultdict(list)
    for filename in sorted(os.listdir(XML_DIR)):
        if not filename.endswith(".xml") or filename == "file_paths.xml":
            continue
        path = os.path.join(XML_DIR, filename)
        root = ElementTree.parse(path).getroot()
        for node in root.iter():
            key = attr(node, "key")
            if not key:
                continue
            keys[key].append(filename)
            enabled = attr(node, "enabled")
            if enabled is not None and enabled.lower() == "false":
                issues.append({"type":"disabled","key":key,"file":filename,"message":f"{filename}: {key} is a visible disabled placeholder"})
            for field in ("title", "summary", "dialogTitle"):
                value = attr(node, field)
                if value and not value.startswith("@") and not value.startswith("?"):
                    issues.append({"type":"hardcoded-text","key":key,"file":filename,"message":f"{filename}: {key} has hardcoded {field}: {value!r}"})

    for key, locations in sorted(keys.items()):
        if len(locations) > 1:
            issues.append({"type":"duplicate","key":key,"message":f"{key}: duplicate preference key in {', '.join(locations)}"})

    out = os.path.join(ROOT, "build", "strict-quality", "ui-surface.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        json.dump({"preferenceCount":len(keys),"issueCount":len(issues),"issues":issues}, handle, indent=2)
        handle.write("\n")

    print(f"UI surface: {len(keys)} key(s), {len(issues)} issue(s)")
    for issue in issues:
        print(f"::error::{issue['message']}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fail when the visible preference surface is not what the product claims.

Four properties, all of them user-visible:

  * a preference key is declared exactly once, so there is one row and one value
  * no row ships as a permanently disabled placeholder
  * no title, summary or dialog title is hardcoded in the XML, which is untranslatable
  * the findings are written to `--out` even when there are none

Exit codes:
    0  the surface is clean
    1  at least one finding
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from xml.etree import ElementTree

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
XML_DIR = os.path.join(REPO, "app", "src", "main", "res", "xml")
DEFAULT_OUT = os.path.join(REPO, "build", "strict-quality", "ui-surface.json")
ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"

TEXT_ATTRIBUTES = ("title", "summary", "dialogTitle")


def attr(node: ElementTree.Element, name: str) -> str | None:
    return node.get(ANDROID + name) or node.get(APP + name)


def collect() -> tuple[dict[str, list[str]], list[dict[str, object]]]:
    """Every keyed row in the preference screens, with the findings found while reading them."""
    keys: dict[str, list[str]] = defaultdict(list)
    issues: list[dict[str, object]] = []

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
                issues.append(
                    {
                        "type": "disabled",
                        "key": key,
                        "file": filename,
                        "message": f"{filename}: {key} is a visible disabled placeholder",
                    }
                )

            for field in TEXT_ATTRIBUTES:
                value = attr(node, field)
                # A literal beginning with %s is the "same as the title" convention, and a
                # value that is already a reference or an attribute is not hardcoded.
                if value and value != "%s" and not value.startswith(("@", "?")):
                    issues.append(
                        {
                            "type": "hardcoded-text",
                            "key": key,
                            "file": filename,
                            "message": f"{filename}: {key} has hardcoded {field}: {value!r}",
                        }
                    )

    return keys, issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--out",
        default=DEFAULT_OUT,
        help=f"where to write the findings JSON (default: {os.path.relpath(DEFAULT_OUT, REPO)})",
    )
    args = parser.parse_args()

    keys, issues = collect()
    for key, locations in sorted(keys.items()):
        if len(locations) > 1:
            issues.append(
                {
                    "type": "duplicate",
                    "key": key,
                    "message": f"{key}: duplicate preference key in {', '.join(locations)}",
                }
            )

    directory = os.path.dirname(args.out)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(
            {
                "preferenceCount": len(keys),
                "issueCount": len(issues),
                "issues": issues,
            },
            handle,
            indent=2,
        )
        handle.write("\n")

    print(f"UI surface: {len(keys)} key(s), {len(issues)} issue(s)")
    for issue in issues:
        print(f"::error::{issue['message']}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())

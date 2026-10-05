#!/usr/bin/env python3
"""Rebrand user-facing strings across every locale.

Runs in Python with explicit UTF-8 because these files contain Arabic, Turkish and
Latin-1 accented text, and a PowerShell rewrite mangles exactly those.

Only user-facing text is touched. Preference keys are not localised and must not
change, so nothing here rewrites a key attribute.
"""
from __future__ import annotations

import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app/src/main/res")

# Ordered so the longer, more specific forms are replaced first.
SUBSTITUTIONS = [
    ("WaEnhancer folder", "WA X folder"),
    ("the WaEnhancer folder", "the WA X folder"),
    ('\\"WaEnhancer\\"', '\\"WA X\\"'),
    ("WaEnhancer module", "WA X module"),
    ("WaEnhancer from", "WA X from"),
    ("so WaEnhancer", "so WA X"),
    ("WaEnhancer can work", "WA X can work"),
    ("WaEnhancer was", "WA X was"),
    ("WaEnhancer is", "WA X is"),
    ("WaEnhancer app", "WA X"),
    ("Wa Enhancer", "WA X"),
    ("WaEnhancer", "WA X"),
]


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8", newline="") as handle:
        return handle.read()


def write(path: str, body: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(body)


def rebrand_file(path: str) -> int:
    body = read(path)
    updated = body
    for needle, replacement in SUBSTITUTIONS:
        updated = updated.replace(needle, replacement)
    if updated == body:
        return 0
    write(path, updated)
    return 1


def main() -> int:
    changed = 0
    files = 0
    for dirname in sorted(os.listdir(RES)):
        if not dirname.startswith("values"):
            continue
        path = os.path.join(RES, dirname, "strings.xml")
        if not os.path.isfile(path):
            continue
        files += 1
        changed += rebrand_file(path)
    print("scanned %d strings.xml files, rewrote %d" % (files, changed))

    # Report anything the substitutions did not catch, so the residue is visible
    # rather than silently shipped.
    residue: list[str] = []
    pattern = re.compile(r"Wa\s?Enhancer|Dev4Mod|t\.me/waenhancer")
    for dirname in sorted(os.listdir(RES)):
        if not dirname.startswith("values"):
            continue
        path = os.path.join(RES, dirname, "strings.xml")
        if not os.path.isfile(path):
            continue
        for number, line in enumerate(read(path).splitlines(), start=1):
            if pattern.search(line):
                residue.append("%s:%d: %s" % (dirname, number, line.strip()))
    if residue:
        print("residual old branding (%d):" % len(residue))
        for line in residue:
            print("  " + line)
    else:
        print("no residual old branding in any locale")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

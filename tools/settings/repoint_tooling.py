#!/usr/bin/env python3
"""Repoint the baseline and compatibility tooling at the new source paths.

These tools hard-code a source path because that is the only thing they measure.
After a package move every one of them silently points at a file that no longer
exists, and the symptom is either a crash or, worse, a report that records nothing.

UTF-8 only; the module paths are ASCII but the assertion output is not.
"""
from __future__ import annotations

import io
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

EDITS = [
    ("tools/baseline/check_baseline.py",
     "app/src/main/java/com/wmods/wppenhacer/xposed/core/devkit/Unobfuscator.kt",
     "app/src/main/java/com/wax/module/xposed/core/devkit/Unobfuscator.kt"),
    ("tools/baseline/rewrite_unobfuscator_bangs.py",
     "app/src/main/java/com/wmods/wppenhacer/xposed/core/devkit/Unobfuscator.kt",
     "app/src/main/java/com/wax/module/xposed/core/devkit/Unobfuscator.kt"),
    ("tools/compatibility/extract_features.py",
     '"applicationId": "com.wmods.wppenhacer"',
     '"applicationId": "com.wax.module"'),
    ("tools/compatibility/extract_features.py",
     '"applicationId": "com.wmods.wppenhacer.w4b"',
     '"applicationId": "com.wax.module.w4b"'),
]


def main() -> int:
    for rel, old, new in EDITS:
        path = os.path.join(ROOT, rel)
        text = io.open(path, encoding="utf-8", newline="").read()
        if old not in text:
            print("skip %s (pattern absent)" % rel)
            continue
        io.open(path, "w", encoding="utf-8", newline="").write(text.replace(old, new))
        print("updated %s" % rel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

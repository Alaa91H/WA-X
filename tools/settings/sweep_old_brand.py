#!/usr/bin/env python3
"""Final old-brand sweep over the source tree.

Runs after the identity migration to catch the residue the structured passes
missed: comments, log tags, native library names, and the documentation that
describes the old project.

`CallRecording.kt` needs 44 replacements because it embeds the product name in a
log tag used pervasively. `dev4mod` in the localised strings is left alone on
purpose: it is an upstream credit, handled in AboutActivity, not active branding.

UTF-8 only. Files under the locales contain Arabic and Turkish text.
"""
from __future__ import annotations

import io
import os
import re
import subprocess

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Generated or owner-maintained files that are rewritten by their own tooling.
SKIP = {
    "app/lint-baseline.xml",
    "app/detekt-baseline.xml",
    "tools/baseline/baseline.json",
    "tools/baseline/BASELINE.md",
    "tools/compatibility/compatibility.json",
    "docs/COMPATIBILITY.md",
    "changelog.txt",
    "tools/settings/migrate_identity.py",
    "tools/settings/rebrand_strings.py",
}

TEXT = (".kt", ".java", ".c", ".cpp", ".h", ".xml", ".txt", ".md", ".properties", ".yml", ".cmake", ".name")

# dev4mod is the upstream author credit and stays in the About notices.
PRESERVE = ("name=\"dev4mod\"",)


def tracked() -> list[str]:
    out = subprocess.run(
        ["git", "ls-files"], cwd=ROOT, capture_output=True, text=True, encoding="utf-8"
    ).stdout
    return out.splitlines()


def main() -> int:
    changed = 0
    residual: list[str] = []
    for rel in tracked():
        if rel in SKIP or not rel.endswith(TEXT):
            continue
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            continue
        try:
            text = io.open(path, encoding="utf-8", newline="").read()
        except (UnicodeDecodeError, OSError):
            continue

        updated = text
        updated = updated.replace("WaEnhancer", "WA X").replace("Wa Enhancer", "WA X")
        updated = updated.replace("Wa EnhancerBusiness", "WA X Business")
        updated = updated.replace("t.me/waenhancer", "t.me/Alaa91h")
        updated = updated.replace("github.com/Dev4Mod", "github.com/Alaa91H")

        # The native library is referenced from CMake and JNI; renaming it keeps the
        # shipped .so names consistent with the product.
        updated = updated.replace("libwaenhancer", "libwax").replace("waenhancer_audio", "wax_audio")

        if updated != text:
            io.open(path, "w", encoding="utf-8", newline="").write(updated)
            changed += 1

        for number, line in enumerate(updated.splitlines(), start=1):
            if "WaEnhancer" in line or "Wa Enhancer" in line or "t.me/waenhancer" in line:
                if any(token in line for token in PRESERVE):
                    continue
                residual.append("%s:%d" % (rel, number))

    print("rewrote %d files" % changed)
    print("residual old-brand references: %d" % len(residual))
    for line in residual:
        print("  " + line)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

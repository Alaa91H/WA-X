#!/usr/bin/env python3
"""WA X identity migration: package, class renames and product branding.

One authoritative pass, in Python with explicit UTF-8 on every read and write.

The first attempt at this migration was driven from PowerShell, and PowerShell 5.1's
``Get-Content -Raw`` reads with the system code page rather than UTF-8. Every file it
touched was silently re-encoded, which turned a file that compiled into one that did
not. Everything here therefore goes through :func:`read_text` and :func:`write_text`,
which refuse to guess an encoding, and the script asserts the tree is clean before it
finishes.

Transforms, in order:

  1. package ``com.wmods.wppenhacer`` -> ``com.wax.module``, and the source trees
  2. class renames: ``WppXposed`` -> ``ModuleEntryPoint``, ``App`` ->
     ``ModuleApplication``, ``WppCore`` -> ``ModuleRuntime``
  3. version properties ``waeVersion*`` -> ``waxVersion*`` (Gradle and backup schema)
  4. product branding: app-facing "WaEnhancer" -> "WA X", old maintainer links ->
     the current ones, public folder name
  5. Xposed entry point file

Generated files (baselines, the compatibility matrix) are deliberately skipped: they
are rewritten by their own generators, and hand-editing them here would be undone on
the next run.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

OLD_PKG = "com.wmods.wppenhacer"
NEW_PKG = "com.wax.module"

TEXT_EXT = {
    ".kt", ".java", ".xml", ".aidl", ".pro", ".txt", ".cmake", ".kts", ".gradle",
    ".properties", ".json", ".yml", ".yaml", ".md", ".c", ".h", ".cpp", ".hpp",
}

SKIP_DIRS = {".git", "build", ".gradle", ".idea", ".kotlin", "__pycache__", "temp"}

GENERATED = {
    "app/detekt-baseline.xml",
    "app/lint-baseline.xml",
    "tools/compatibility/compatibility.json",
    "docs/COMPATIBILITY.md",
    "tools/baseline/baseline.json",
    "tools/baseline/BASELINE.md",
    "docs/SETTING_MIGRATION_MATRIX.md",
}

# (pattern, replacement, note) applied to every text file, after the package swap.
REPLACEMENTS: list[tuple[str, str, str]] = [
    # class and file renames
    (r"\bclass WppXposed\b", "class ModuleEntryPoint", "entry point class"),
    (r"\bWppXposed\b", "ModuleEntryPoint", "entry point references"),
    (r"\bobject WppCore\b", "object ModuleRuntime", "runtime object"),
    (r"\bWppCore\b", "ModuleRuntime", "runtime references"),
    (r"\bclass App\b(?!lication)", "class ModuleApplication", "application class"),
    (r"\bApp::class\b", "ModuleApplication::class", "application class literal"),
    (r"import com\.wax\.module\.App\b", "import com.wax.module.ModuleApplication", "application import"),
    (r"(?<![\w.])App\.", "ModuleApplication.", "application member access"),
    # Bare type positions: a field, parameter or return type spelled `App`.
    (r"(\s*:\s*)App\b", r"\1ModuleApplication", "application type annotation"),
    # version properties, both the Gradle property and the backup schema field
    (r"\bwaeVersionName\b", "waxVersionName", "version property"),
    (r"\bwaeVersionCode\b", "waxVersionCode", "version code property"),
    (r"\bwaeVersion\b", "waxVersion", "backup schema field"),
    (r"\bWaeVersion\b", "WaxVersion", "backup schema field"),
    # product branding in app-facing text
    (r'"WaEnhancer feature failure report', '"WA X feature failure report', "failure report header"),
    (r'"WaEnhancer Compatibility Report"', '"WA X Compatibility Report"', "compatibility report header"),
    (r"WaEnhancer failed to start repeatedly", "WA X failed to start repeatedly", "safe mode message"),
    (r"so WaEnhancer started conservatively", "so WA X started conservatively", "safe mode message"),
    (r"Wa EnhancerBusiness", "WA X Business", "business app label"),
    (r"\bWa Enhancer\b", "WA X", "product name"),
    # identifiers and paths carrying the old brand
    (r"\bwaEnhancerFolder\b", "MODULE_FOLDER", "public folder property"),
    (r'"Download/WaEnhancer/themes"', '"Download/WA-X/themes"', "theme folder"),
    (r'val TAG = "WaEnhancer"', 'val TAG = "WA-X"', "log tag"),
    # maintainer and project links
    (r"https://t\.me/waenhancer", "https://t.me/Alaa91h", "telegram"),
    (r"https://github\.com/Dev4Mod/WaEnhancer", "https://github.com/Alaa91H/WA-X", "repository"),
    (r"https://github\.com/Dev4Mod", "https://github.com/Alaa91H", "github owner"),
    (r"https://t\.me/Dev4Mod", "https://t.me/Alaa91h", "telegram"),
]


def read_text(path: str) -> str:
    with open(path, "r", encoding="utf-8", newline="") as handle:
        return handle.read()


def write_text(path: str, body: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(body)


def move_trees() -> None:
    moves = [
        (f"app/src/{set_name}/java/com/wmods/wppenhacer", f"app/src/{set_name}/java/com/wax/module")
        for set_name in ("main", "test", "androidTest")
    ]
    for src, dst in moves:
        if not os.path.isdir(src):
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        subprocess.run(["git", "mv", src, dst], cwd=ROOT, check=True)
        print("moved %s" % src)
    # The AIDL tree is a separate source root and is easy to miss: renaming only the
    # java directory leaves the generated bridge interface in the old package, and the
    # failure surfaces as "Unresolved reference WaeIIFace" much later.
    for extra in ("aidl",):
        src = f"app/src/main/{extra}/com/wmods/wppenhacer"
        if not os.path.isdir(src):
            continue
        dst = f"app/src/main/{extra}/com/wax/module"
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        subprocess.run(["git", "mv", src, dst], cwd=ROOT, check=True)
        print("moved %s (extra source root)" % src)
    for base in ("main", "test", "androidTest"):
        stale = f"app/src/{base}/java/com/wmods"
        if os.path.isdir(stale) and not os.listdir(stale):
            os.rmdir(stale)
    stale_aidl = "app/src/main/aidl/com/wmods"
    if os.path.isdir(stale_aidl) and not os.listdir(stale_aidl):
        os.rmdir(stale_aidl)


def rename_files() -> None:
    renames = [
        ("app/src/main/java/com/wax/module/WppXposed.kt", "app/src/main/java/com/wax/module/ModuleEntryPoint.kt"),
        ("app/src/main/java/com/wax/module/App.kt", "app/src/main/java/com/wax/module/ModuleApplication.kt"),
        ("app/src/main/java/com/wax/module/xposed/core/WppCore.kt",
         "app/src/main/java/com/wax/module/xposed/core/ModuleRuntime.kt"),
    ]
    for src, dst in renames:
        if os.path.isfile(src):
            subprocess.run(["git", "mv", src, dst], cwd=ROOT, check=True)
            print("renamed %s" % os.path.basename(dst))


def transform(body: str) -> str:
    if OLD_PKG in body:
        body = body.replace(OLD_PKG, NEW_PKG)
    for pattern, replacement, _note in REPLACEMENTS:
        body = re.sub(pattern, replacement, body)
    return body


def rewrite_all() -> int:
    changed = 0
    skipped: list[str] = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for filename in filenames:
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            if os.path.splitext(filename)[1] not in TEXT_EXT:
                continue
            if rel in GENERATED:
                skipped.append(rel)
                continue
            try:
                body = read_text(path)
            except (UnicodeDecodeError, OSError):
                continue
            updated = transform(body)
            if updated != body:
                write_text(path, updated)
                changed += 1
    print("rewrote %d files" % changed)
    for rel in sorted(skipped):
        print("  skipped generated: %s" % rel)
    return changed


def verify_encoding() -> list[str]:
    """Fail loudly if any source file stopped being valid UTF-8."""
    broken: list[str] = []
    for dirpath, dirnames, filenames in os.walk(os.path.join(ROOT, "app", "src")):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for filename in filenames:
            if not filename.endswith((".kt", ".java", ".xml", ".aidl")):
                continue
            path = os.path.join(dirpath, filename)
            try:
                open(path, encoding="utf-8").read()
            except (UnicodeDecodeError, OSError) as exc:
                broken.append("%s (%s)" % (os.path.relpath(path, ROOT), exc))
    return broken


def write_entry_point() -> None:
    path = os.path.join(ROOT, "app/src/main/assets/xposed_init")
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write("%s.ModuleEntryPoint\n" % NEW_PKG)
    print("wrote assets/xposed_init -> %s.ModuleEntryPoint" % NEW_PKG)


def main() -> int:
    move_trees()
    rename_files()
    rewrite_all()
    write_entry_point()
    broken = verify_encoding()
    if broken:
        print("ENCODING FAILURE in %d file(s):" % len(broken))
        for line in broken:
            print("  " + line)
        return 1
    print("encoding verified: every source file is valid UTF-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Self-test for tools/quality/check_lsposed_contract.py.

The checker's value is that it fails when the loader contract breaks, so the test is a
mutation suite: build a tree that satisfies the contract, break exactly one thing, and assert
the checker reports that check and exits 1. A checker that only ever passes would be worse than
no checker, and this is what stops it from silently grading itself.

Usage:
    python3 tools/quality/test_check_lsposed_contract.py
Exit codes: 0 every case behaved as expected, 1 a case did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load_checker():
    spec = importlib.util.spec_from_file_location(
        "check_lsposed_contract", os.path.join(HERE, "check_lsposed_contract.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECKER = load_checker()

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <meta-data android:name="xposedmodule" android:value="__MODULE__" />
        <meta-data android:name="xposeddescription" android:value="fixture" />
        <meta-data android:name="xposedminversion" android:value="__MINVERSION__" />
        <meta-data android:name="xposedsharedprefs" android:value="__SHAREDPREFS__" />
        <meta-data android:name="xposedscope" android:resource="__SCOPE__" />
    </application>
</manifest>
"""

ARRAYS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="scope">
__ITEMS__    </string-array>
</resources>
"""

ENTRY = "com.example.hook.EntryPoint\n"

SOURCE = "package com.example.hook\n\nobject EntryPoint\n"

CATALOG = """[versions]
xposed-legacy = "82"

[libraries]
__COORD__
"""


def write(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def fixture(root: str, **overrides: str) -> None:
    """Write a tree that satisfies the contract, with named parts replaced when asked."""
    manifest = MANIFEST
    manifest = manifest.replace("__MODULE__", overrides.get("module", "true"))
    manifest = manifest.replace("__MINVERSION__", overrides.get("minversion", "93"))
    manifest = manifest.replace("__SHAREDPREFS__", overrides.get("sharedprefs", "true"))
    manifest = manifest.replace("__SCOPE__", overrides.get("scope", "@array/scope"))
    write(os.path.join(root, "app/src/main/AndroidManifest.xml"), manifest)

    items = overrides.get("scope_items", ["android", "com.whatsapp"])
    body = "".join("        <item>%s</item>\n" % item for item in items)
    arrays = ARRAYS.replace("__ITEMS__", body)
    if overrides.get("scope_array_name"):
        arrays = arrays.replace('name="scope"', 'name="%s"' % overrides["scope_array_name"])
    write(os.path.join(root, "app/src/main/res/values/arrays.xml"), arrays)

    if overrides.get("entry") is not False:
        write(os.path.join(root, "app/src/main/assets/xposed_init"), overrides.get("entry", ENTRY))
    if overrides.get("source") is not False:
        write(
            os.path.join(root, "app/src/main/java/com/example/hook/EntryPoint.kt"),
            overrides.get("source", SOURCE),
        )
    if overrides.get("catalog") is not False:
        coordinate = overrides.get(
            "catalog", 'libxposed-legacy = { group = "de.robv.android.xposed", name = "api" }'
        )
        write(os.path.join(root, "gradle/libs.versions.toml"), CATALOG.replace("__COORD__", coordinate))

    for relative in overrides.get("extra_files", []):
        write(os.path.join(root, relative), "x\n")


def run(root: str) -> tuple[int, list[dict]]:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = CHECKER.main(["--root", root, "--format", "json"])
    output = buffer.getvalue().strip()
    violations = json.loads(output)["violations"] if output else []
    return code, violations


def case(name: str, expected_check: str | None, **overrides: str) -> bool:
    root = tempfile.mkdtemp(prefix="lsposed-contract-")
    try:
        fixture(root, **overrides)
        code, violations = run(root)
        checks = {violation["check"] for violation in violations}
        if expected_check is None:
            if code != 0 or violations:
                print("[fail] %s: expected a clean run, got exit %d and %s" % (name, code, violations))
                return False
            return True
        if code != 1:
            print("[fail] %s: expected exit 1, got %d (%s)" % (name, code, violations))
            return False
        if expected_check not in checks:
            print("[fail] %s: expected check %r, got %s" % (name, expected_check, sorted(checks)))
            return False
        return True
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    cases = [
        ("a contract-satisfying tree is clean", None, {}),
        ("a missing xposedmodule flag is caught", "manifest.xposedmodule", {"module": "false"}),
        ("xposedmodule must be exactly true", "manifest.xposedmodule", {"module": "TRUEish"}),
        ("a missing xposedminversion is caught", "manifest.xposedminversion", {"minversion": ""}),
        ("an API level below the module's is caught", "manifest.xposedminversion", {"minversion": "82"}),
        ("a non-numeric API level is caught", "manifest.xposedminversion", {"minversion": "latest"}),
        ("an API level above the legacy range is caught", "manifest.xposedminversion", {"minversion": "101"}),
        ("a missing xposedsharedprefs is caught", "manifest.xposedsharedprefs", {"sharedprefs": ""}),
        ("a missing xposedscope is caught", "manifest.xposedscope", {"scope": ""}),
        ("a scope resource that is not an array is caught", "manifest.xposedscope", {"scope": "@string/scope"}),
        (
            "a scope pointing at a missing array is caught",
            "manifest.xposedscope",
            {"scope": "@array/not_there"},
        ),
        ("an empty scope array is caught", "manifest.xposedscope", {"scope_items": []}),
        (
            "a scope without the framework entry is caught",
            "manifest.xposedscope",
            {"scope_items": ["com.whatsapp"]},
        ),
        ("a missing entry point file is caught", "entry.file", {"entry": False}),
        ("an empty entry point file is caught", "entry.single", {"entry": "\n# comment\n"}),
        ("two entry points are caught", "entry.single", {"entry": "a.B\nc.D\n"}),
        ("a missing entry class is caught", "entry.class", {"source": False}),
        (
            "a source file without the entry class is caught",
            "entry.class",
            {"source": "package com.example.hook\n\nobject SomethingElse\n"},
        ),
        (
            "a modern-API META-INF/xposed directory is caught",
            "modern.meta",
            {"extra_files": ["app/src/main/resources/META-INF/xposed/module.prop"]},
        ),
        ("a missing legacy API dependency is caught", "api.pinned", {"catalog": 'something = { group = "x", name = "y" }'}),
        ("a missing version catalog is caught", "api.pinned", {"catalog": False}),
    ]

    failed = 0
    for name, expected_check, overrides in cases:
        if case(name, expected_check, **overrides):
            print("[pass] %s" % name)
        else:
            failed += 1

    # The real repository has to satisfy the contract this checker enforces.
    code, violations = run(REPO_ROOT)
    if code == 0 and not violations:
        print("[pass] the real repository satisfies the LSPosed loader contract")
    else:
        print("[fail] the real repository violates the contract: %s" % violations)
        failed += 1

    total = len(cases) + 1
    if failed:
        print("%d of %d contract cases failed" % (failed, total), file=sys.stderr)
        return 1
    print("all %d contract cases behaved correctly" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
